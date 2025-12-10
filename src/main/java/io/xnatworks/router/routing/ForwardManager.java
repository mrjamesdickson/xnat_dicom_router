/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.routing;

import io.xnatworks.router.anon.ScriptLibrary;
import io.xnatworks.router.config.AppConfig;
import io.xnatworks.router.dicom.DicomClient;
import io.xnatworks.router.dicom.DicomReceiver;
import io.xnatworks.router.tracking.TransferTracker;
import io.xnatworks.router.xnat.XnatClient;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.io.DicomOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Manages forwarding of DICOM studies to configured destinations.
 * Handles:
 * - Conditional routing based on DICOM attributes
 * - Validation rules
 * - Filtering
 * - Tag modifications
 * - Anonymization
 * - Multi-destination forwarding
 * - Rate limiting
 * - Retry logic
 */
public class ForwardManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ForwardManager.class);

    private final AppConfig config;
    private final DestinationManager destinationManager;
    private final TransferTracker transferTracker;
    private final ScriptLibrary scriptLibrary;

    // Thread pools per route (keyed by AE Title)
    private final Map<String, ExecutorService> routeExecutors = new ConcurrentHashMap<>();

    // Rate limiters per route
    private final Map<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();

    // Pending retries
    private final ScheduledExecutorService retryScheduler;

    public ForwardManager(AppConfig config,
                          DestinationManager destinationManager,
                          TransferTracker transferTracker,
                          ScriptLibrary scriptLibrary) {
        this.config = config;
        this.destinationManager = destinationManager;
        this.transferTracker = transferTracker;
        this.scriptLibrary = scriptLibrary;

        this.retryScheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "forward-retry-scheduler");
            t.setDaemon(true);
            return t;
        });

        initializeRouteExecutors();
    }

    /**
     * Initialize thread pools for each route.
     */
    private void initializeRouteExecutors() {
        for (AppConfig.RouteConfig route : config.getRoutes()) {
            if (!route.isEnabled()) continue;

            int threads = route.getWorkerThreads();
            ExecutorService executor = Executors.newFixedThreadPool(threads, r -> {
                Thread t = new Thread(r, "forward-worker-" + route.getAeTitle());
                t.setDaemon(true);
                return t;
            });
            routeExecutors.put(route.getAeTitle(), executor);

            // Initialize rate limiter if configured
            if (route.getRateLimitPerMinute() > 0) {
                rateLimiters.put(route.getAeTitle(),
                        new RateLimiter(route.getRateLimitPerMinute()));
            }

            log.info("Initialized {} worker threads for route '{}'", threads, route.getAeTitle());
        }
    }

    /**
     * Process a received study through the routing pipeline.
     */
    public CompletableFuture<ForwardResult> processStudy(DicomReceiver.ReceivedStudy study,
                                                          AppConfig.RouteConfig route) {
        ExecutorService executor = routeExecutors.get(route.getAeTitle());
        if (executor == null) {
            return CompletableFuture.completedFuture(
                    ForwardResult.failed("No executor for route: " + route.getAeTitle()));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                return doProcessStudy(study, route);
            } catch (Exception e) {
                log.error("[{}] Error processing study {}: {}",
                        route.getAeTitle(), study.getStudyUid(), e.getMessage(), e);
                return ForwardResult.failed(e.getMessage());
            }
        }, executor);
    }

    /**
     * Main processing pipeline for a study.
     */
    private ForwardResult doProcessStudy(DicomReceiver.ReceivedStudy study,
                                          AppConfig.RouteConfig route) throws Exception {
        String aeTitle = route.getAeTitle();
        String studyUid = study.getStudyUid();

        log.info("[{}] Processing study: {} ({} files)", aeTitle, studyUid, study.getFileCount());

        // Check rate limit
        RateLimiter limiter = rateLimiters.get(aeTitle);
        if (limiter != null && !limiter.tryAcquire()) {
            log.warn("[{}] Rate limit exceeded, queueing study: {}", aeTitle, studyUid);
            // Queue for later processing
            scheduleRetry(study, route, 0);
            return ForwardResult.queued("Rate limit exceeded");
        }

        // Create transfer record
        TransferTracker.TransferRecord transfer = transferTracker.createTransfer(
                aeTitle, studyUid, study.getCallingAeTitle(),
                study.getFileCount(), study.getTotalSize());

        try {
            // 1. Read DICOM attributes for routing decisions
            Attributes studyAttributes = readStudyAttributes(study.getPath());
            if (studyAttributes == null) {
                transferTracker.failTransfer(transfer.getId(), "Failed to read DICOM attributes");
                return ForwardResult.failed("Failed to read DICOM attributes");
            }

            // 2. Validate against rules
            ValidationResult validation = validateStudy(studyAttributes, route);
            if (!validation.isValid() && validation.getAction().equals("reject")) {
                transferTracker.failTransfer(transfer.getId(), "Validation failed: " + validation.getMessage());
                moveToFailed(study, route, "Validation failed: " + validation.getMessage());
                return ForwardResult.failed("Validation failed: " + validation.getMessage());
            }

            // 3. Apply filters
            if (!passesFilters(studyAttributes, route)) {
                log.info("[{}] Study {} filtered out by rules", aeTitle, studyUid);
                transferTracker.failTransfer(transfer.getId(), "Filtered out by rules");
                moveToFailed(study, route, "Filtered by rules");
                return ForwardResult.filtered("Study filtered by rules");
            }

            // 4. Determine destinations (conditional routing)
            List<AppConfig.RouteDestination> destinations = determineDestinations(studyAttributes, route);
            if (destinations.isEmpty()) {
                transferTracker.failTransfer(transfer.getId(), "No destinations matched routing rules");
                return ForwardResult.failed("No destinations matched");
            }

            log.info("[{}] Routing study {} to {} destination(s): {}",
                    aeTitle, studyUid, destinations.size(),
                    destinations.stream().map(AppConfig.RouteDestination::getDestination)
                            .collect(Collectors.joining(", ")));

            // 5. Mark as processing
            transferTracker.startProcessing(transfer.getId());

            // 6. Apply tag modifications (if any)
            Path processingDir = prepareProcessingDir(study, route);
            copyFilesToProcessing(study.getPath(), processingDir);

            if (!route.getTagModifications().isEmpty()) {
                applyTagModifications(processingDir, route.getTagModifications());
            }

            // 7. Start forwarding
            transferTracker.startForwarding(transfer.getId(),
                    destinations.stream().map(AppConfig.RouteDestination::getDestination)
                            .collect(Collectors.toList()));

            // 8. Forward to each destination
            ForwardResult result = new ForwardResult();
            result.setStudyUid(studyUid);
            result.setTotalDestinations(destinations.size());

            for (AppConfig.RouteDestination routeDest : destinations) {
                try {
                    DestinationForwardResult destResult = forwardToDestination(
                            processingDir, studyAttributes, routeDest, route);

                    transferTracker.updateDestinationResult(
                            transfer.getId(),
                            routeDest.getDestination(),
                            destResult.isSuccess() ?
                                    TransferTracker.DestinationStatus.SUCCESS :
                                    TransferTracker.DestinationStatus.FAILED,
                            destResult.getMessage(),
                            destResult.getDurationMs(),
                            destResult.getFilesTransferred());

                    if (destResult.isSuccess()) {
                        result.incrementSuccess();
                    } else {
                        result.incrementFailed();
                        // Schedule retry if configured
                        if (routeDest.getRetryCount() > 0) {
                            scheduleDestinationRetry(processingDir, studyAttributes,
                                    routeDest, route, 1);
                        }
                    }

                } catch (Exception e) {
                    log.error("[{}] Failed to forward to {}: {}",
                            aeTitle, routeDest.getDestination(), e.getMessage());
                    result.incrementFailed();

                    transferTracker.updateDestinationResult(
                            transfer.getId(),
                            routeDest.getDestination(),
                            TransferTracker.DestinationStatus.FAILED,
                            e.getMessage(), 0, 0);
                }
            }

            // 9. Move to completed or failed based on results
            if (result.getSuccessCount() > 0) {
                moveToCompleted(study, route);
            } else {
                moveToFailed(study, route, "All destinations failed");
            }

            // Cleanup processing directory
            deleteDirectory(processingDir);

            result.setStatus(result.getFailedCount() == 0 ? "completed" :
                    result.getSuccessCount() > 0 ? "partial" : "failed");

            return result;

        } catch (Exception e) {
            transferTracker.failTransfer(transfer.getId(), e.getMessage());
            throw e;
        }
    }

    /**
     * Forward to a specific destination.
     */
    private DestinationForwardResult forwardToDestination(Path sourceDir,
                                                           Attributes studyAttributes,
                                                           AppConfig.RouteDestination routeDest,
                                                           AppConfig.RouteConfig route) throws Exception {
        String destName = routeDest.getDestination();
        AppConfig.Destination dest = config.getDestination(destName);

        if (dest == null) {
            return new DestinationForwardResult(false, "Destination not found: " + destName);
        }

        if (!dest.isEnabled()) {
            return new DestinationForwardResult(false, "Destination disabled: " + destName);
        }

        // Check destination health
        if (!destinationManager.isAvailable(destName)) {
            return new DestinationForwardResult(false, "Destination unavailable: " + destName);
        }

        long startTime = System.currentTimeMillis();

        // Prepare files (with anonymization if needed)
        Path forwardDir = sourceDir;
        if (routeDest.isAnonymize()) {
            forwardDir = applyAnonymization(sourceDir, routeDest, route);
        }

        // Get files to forward
        List<File> files = Files.list(forwardDir)
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".dcm"))
                .map(Path::toFile)
                .collect(Collectors.toList());

        DestinationForwardResult result;

        if (dest instanceof AppConfig.XnatDestination) {
            result = forwardToXnat(files, (AppConfig.XnatDestination) dest, destName, routeDest);
        } else if (dest instanceof AppConfig.DicomAeDestination) {
            result = forwardToDicom(files, (AppConfig.DicomAeDestination) dest, destName);
        } else if (dest instanceof AppConfig.FileDestination) {
            result = forwardToFile(files, (AppConfig.FileDestination) dest, destName,
                    studyAttributes, route.getAeTitle());
        } else {
            result = new DestinationForwardResult(false, "Unknown destination type");
        }

        result.setDurationMs(System.currentTimeMillis() - startTime);
        result.setFilesTransferred(result.isSuccess() ? files.size() : 0);

        // Cleanup anonymized directory if we created one
        if (routeDest.isAnonymize() && !forwardDir.equals(sourceDir)) {
            deleteDirectory(forwardDir);
        }

        return result;
    }

    /**
     * Forward to XNAT destination.
     */
    private DestinationForwardResult forwardToXnat(List<File> files,
                                                    AppConfig.XnatDestination dest,
                                                    String destName,
                                                    AppConfig.RouteDestination routeDest) {
        XnatClient client = destinationManager.getXnatClient(destName);
        if (client == null) {
            return new DestinationForwardResult(false, "XNAT client not initialized");
        }

        try {
            // Create ZIP file
            Path zipFile = createZip(files);

            // Upload to XNAT with retry settings from config
            XnatClient.UploadResult uploadResult = client.uploadWithRetry(
                    zipFile.toFile(),
                    routeDest.getProjectId(),
                    null,  // Subject generated from DICOM
                    null,  // Session generated from DICOM
                    routeDest.isAutoArchive(),
                    routeDest.getRetryCount(),
                    routeDest.getRetryDelaySeconds() * 1000L
            );

            // Cleanup ZIP
            Files.deleteIfExists(zipFile);

            if (uploadResult.isSuccess()) {
                return new DestinationForwardResult(true, "Uploaded to XNAT");
            } else {
                return new DestinationForwardResult(false, uploadResult.getErrorMessage());
            }

        } catch (Exception e) {
            return new DestinationForwardResult(false, "XNAT upload failed: " + e.getMessage());
        }
    }

    /**
     * Forward to DICOM AE destination.
     */
    private DestinationForwardResult forwardToDicom(List<File> files,
                                                     AppConfig.DicomAeDestination dest,
                                                     String destName) {
        DicomClient client = destinationManager.getDicomClient(destName);
        if (client == null) {
            return new DestinationForwardResult(false, "DICOM client not initialized");
        }

        try {
            DicomClient.StoreResult storeResult = client.store(files);

            if (storeResult.isSuccess()) {
                return new DestinationForwardResult(true,
                        String.format("Stored %d files", storeResult.getSuccessCount()));
            } else {
                return new DestinationForwardResult(false,
                        String.format("Failed: %d/%d files", storeResult.getFailedCount(),
                                storeResult.getTotalFiles()));
            }

        } catch (Exception e) {
            return new DestinationForwardResult(false, "DICOM store failed: " + e.getMessage());
        }
    }

    /**
     * Forward to file destination.
     */
    private DestinationForwardResult forwardToFile(List<File> files,
                                                    AppConfig.FileDestination dest,
                                                    String destName,
                                                    Attributes studyAttributes,
                                                    String aeTitle) {
        try {
            // Build target directory based on pattern
            String dirPattern = dest.getDirectoryPattern();
            String targetSubDir = expandPattern(dirPattern, studyAttributes);

            Path targetDir = Paths.get(dest.getPath());
            if (dest.isOrganizeByAe()) {
                targetDir = targetDir.resolve(aeTitle);
            }
            targetDir = targetDir.resolve(targetSubDir);

            Files.createDirectories(targetDir);

            int copied = 0;
            for (File file : files) {
                Files.copy(file.toPath(), targetDir.resolve(file.getName()),
                        StandardCopyOption.REPLACE_EXISTING);
                copied++;
            }

            return new DestinationForwardResult(true,
                    String.format("Copied %d files to %s", copied, targetDir));

        } catch (Exception e) {
            return new DestinationForwardResult(false, "File copy failed: " + e.getMessage());
        }
    }

    /**
     * Apply anonymization using script from library.
     */
    private Path applyAnonymization(Path sourceDir, AppConfig.RouteDestination routeDest,
                                     AppConfig.RouteConfig route) throws Exception {
        String scriptName = routeDest.getEffectiveAnonScript();

        // Get script content
        String scriptContent;
        try {
            scriptContent = scriptLibrary.getScriptContent(scriptName);
        } catch (Exception e) {
            log.warn("Script '{}' not found, using passthrough", scriptName);
            scriptContent = scriptLibrary.getScriptContent("passthrough");
        }

        // Create anonymized output directory
        Path anonDir = sourceDir.getParent().resolve("anonymized_" + System.currentTimeMillis());
        Files.createDirectories(anonDir);

        // Write script to temp file
        Path scriptFile = Files.createTempFile("anon_script_", ".das");
        Files.writeString(scriptFile, scriptContent);

        try {
            // Find DicomEdit JAR
            Path dicomEditJar = findDicomEditJar();
            if (dicomEditJar == null) {
                throw new IOException("DicomEdit JAR not found");
            }

            // Run DicomEdit
            ProcessBuilder pb = new ProcessBuilder(
                    "java", "-jar", dicomEditJar.toString(),
                    "-s", scriptFile.toString(),
                    "-i", sourceDir.toString(),
                    "-o", anonDir.toString()
            );
            pb.redirectErrorStream(true);

            Process process = pb.start();
            boolean completed = process.waitFor(5, TimeUnit.MINUTES);

            if (!completed || process.exitValue() != 0) {
                throw new IOException("DicomEdit failed");
            }

            return anonDir;

        } finally {
            Files.deleteIfExists(scriptFile);
        }
    }

    /**
     * Determine which destinations to use based on routing rules.
     */
    private List<AppConfig.RouteDestination> determineDestinations(Attributes attrs,
                                                                    AppConfig.RouteConfig route) {
        // Check routing rules in order
        for (AppConfig.RoutingRule rule : route.getRoutingRules()) {
            if (matchesRule(attrs, rule)) {
                log.debug("Routing rule '{}' matched", rule.getName());

                // Map rule destinations to RouteDestination objects
                List<AppConfig.RouteDestination> matched = new ArrayList<>();
                for (String destName : rule.getDestinations()) {
                    // Find matching RouteDestination or create default
                    AppConfig.RouteDestination routeDest = route.getDestinations().stream()
                            .filter(d -> d.getDestination().equals(destName))
                            .findFirst()
                            .orElseGet(() -> {
                                AppConfig.RouteDestination d = new AppConfig.RouteDestination();
                                d.setDestination(destName);
                                return d;
                            });
                    matched.add(routeDest);
                }
                return matched;
            }
        }

        // No rules matched - return all enabled destinations
        return route.getDestinations().stream()
                .filter(AppConfig.RouteDestination::isEnabled)
                .sorted(Comparator.comparingInt(AppConfig.RouteDestination::getPriority))
                .collect(Collectors.toList());
    }

    /**
     * Check if DICOM attributes match a routing rule.
     */
    private boolean matchesRule(Attributes attrs, AppConfig.RoutingRule rule) {
        String tagValue = getTagValue(attrs, rule.getTag());
        if (tagValue == null) tagValue = "";

        String operator = rule.getOperator().toLowerCase();
        String ruleValue = rule.getValue();
        List<String> ruleValues = rule.getValues();

        switch (operator) {
            case "equals":
                return tagValue.equals(ruleValue);
            case "contains":
                return tagValue.contains(ruleValue);
            case "starts_with":
                return tagValue.startsWith(ruleValue);
            case "ends_with":
                return tagValue.endsWith(ruleValue);
            case "matches":
                return Pattern.matches(ruleValue, tagValue);
            case "in":
                return ruleValues.contains(tagValue);
            default:
                return false;
        }
    }

    /**
     * Validate study against validation rules.
     */
    private ValidationResult validateStudy(Attributes attrs, AppConfig.RouteConfig route) {
        for (AppConfig.ValidationRule rule : route.getValidationRules()) {
            if (!passesValidation(attrs, rule)) {
                return new ValidationResult(false, rule.getOnFailure(),
                        "Validation failed: " + rule.getName());
            }
        }
        return new ValidationResult(true, "pass", "All validations passed");
    }

    private boolean passesValidation(Attributes attrs, AppConfig.ValidationRule rule) {
        String tagValue = getTagValue(attrs, rule.getTag());

        switch (rule.getType().toLowerCase()) {
            case "required_tag":
                return tagValue != null && !tagValue.isEmpty();

            case "tag_value":
                if (tagValue == null) return false;
                return matchesOperator(tagValue, rule.getOperator(), rule.getValue(), rule.getValues());

            case "tag_length":
                if (tagValue == null) return true;  // Only validate if present
                int len = tagValue.length();
                if (rule.getMinLength() != null && len < rule.getMinLength()) return false;
                if (rule.getMaxLength() != null && len > rule.getMaxLength()) return false;
                return true;

            default:
                return true;
        }
    }

    /**
     * Check if study passes filter rules.
     */
    private boolean passesFilters(Attributes attrs, AppConfig.RouteConfig route) {
        for (AppConfig.FilterRule filter : route.getFilters()) {
            String tagValue = getTagValue(attrs, filter.getTag());
            if (tagValue == null) tagValue = "";

            boolean matches = matchesOperator(tagValue, filter.getOperator(),
                    filter.getValue(), filter.getValues());

            if (filter.getAction().equals("exclude") && matches) {
                return false;  // Exclude matched
            }
            if (filter.getAction().equals("include") && !matches) {
                return false;  // Include didn't match
            }
        }
        return true;
    }

    private boolean matchesOperator(String tagValue, String operator, String value, List<String> values) {
        if (operator == null) operator = "equals";

        switch (operator.toLowerCase()) {
            case "equals":
                return tagValue.equals(value);
            case "contains":
                return tagValue.contains(value);
            case "starts_with":
                return tagValue.startsWith(value);
            case "ends_with":
                return tagValue.endsWith(value);
            case "matches":
                return Pattern.matches(value, tagValue);
            case "in":
                return values != null && values.contains(tagValue);
            default:
                return false;
        }
    }

    /**
     * Apply tag modifications to DICOM files.
     * Supports actions: set, remove, copy, hash
     */
    private void applyTagModifications(Path dir, List<AppConfig.TagModification> modifications)
            throws Exception {
        if (modifications == null || modifications.isEmpty()) {
            return;
        }

        // Get all DICOM files in directory
        List<Path> dicomFiles;
        try (var stream = Files.list(dir)) {
            dicomFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".dcm") || !p.toString().contains("."))
                    .collect(Collectors.toList());
        }

        log.info("Applying {} tag modifications to {} DICOM files", modifications.size(), dicomFiles.size());

        for (Path dicomFile : dicomFiles) {
            try {
                // Read the DICOM file
                Attributes fmi;
                Attributes dataset;
                String transferSyntax;

                try (DicomInputStream dis = new DicomInputStream(dicomFile.toFile())) {
                    fmi = dis.readFileMetaInformation();
                    dataset = dis.readDataset();
                    transferSyntax = dis.getTransferSyntax();
                }

                boolean modified = false;

                // Apply each modification
                for (AppConfig.TagModification mod : modifications) {
                    int tag = parseTag(mod.getTag());
                    if (tag == -1) {
                        log.warn("Invalid tag specification: {}", mod.getTag());
                        continue;
                    }

                    String action = mod.getAction() != null ? mod.getAction().toLowerCase() : "set";

                    switch (action) {
                        case "set":
                            // Set tag to specified value
                            String value = mod.getValue();
                            if (value != null) {
                                VR vr = dataset.getVR(tag);
                                if (vr == null) {
                                    vr = guessVR(tag);
                                }
                                dataset.setString(tag, vr, value);
                                modified = true;
                                log.debug("Set tag {} to '{}'", mod.getTag(), value);
                            }
                            break;

                        case "remove":
                            // Remove the tag
                            if (dataset.contains(tag)) {
                                dataset.remove(tag);
                                modified = true;
                                log.debug("Removed tag {}", mod.getTag());
                            }
                            break;

                        case "copy":
                            // Copy value from another tag
                            if (mod.getValue() != null) {
                                int sourceTag = parseTag(mod.getValue());
                                if (sourceTag != -1) {
                                    String sourceValue = dataset.getString(sourceTag);
                                    if (sourceValue != null) {
                                        VR vr = dataset.getVR(tag);
                                        if (vr == null) vr = guessVR(tag);
                                        dataset.setString(tag, vr, sourceValue);
                                        modified = true;
                                        log.debug("Copied tag {} from {}", mod.getTag(), mod.getValue());
                                    }
                                }
                            }
                            break;

                        case "hash":
                            // Hash the current value (for pseudo-anonymization)
                            String currentValue = dataset.getString(tag);
                            if (currentValue != null && !currentValue.isEmpty()) {
                                String hashedValue = hashValue(currentValue);
                                VR vr = dataset.getVR(tag);
                                if (vr == null) vr = guessVR(tag);
                                dataset.setString(tag, vr, hashedValue);
                                modified = true;
                                log.debug("Hashed tag {}", mod.getTag());
                            }
                            break;

                        case "prefix":
                            // Add prefix to existing value
                            String prefixValue = dataset.getString(tag);
                            if (prefixValue != null && mod.getValue() != null) {
                                VR vr = dataset.getVR(tag);
                                if (vr == null) vr = guessVR(tag);
                                dataset.setString(tag, vr, mod.getValue() + prefixValue);
                                modified = true;
                                log.debug("Prefixed tag {} with '{}'", mod.getTag(), mod.getValue());
                            }
                            break;

                        case "suffix":
                            // Add suffix to existing value
                            String suffixValue = dataset.getString(tag);
                            if (suffixValue != null && mod.getValue() != null) {
                                VR vr = dataset.getVR(tag);
                                if (vr == null) vr = guessVR(tag);
                                dataset.setString(tag, vr, suffixValue + mod.getValue());
                                modified = true;
                                log.debug("Suffixed tag {} with '{}'", mod.getTag(), mod.getValue());
                            }
                            break;

                        default:
                            log.warn("Unknown tag modification action: {}", action);
                    }
                }

                // Write back if modified
                if (modified) {
                    try (DicomOutputStream dos = new DicomOutputStream(dicomFile.toFile())) {
                        dos.writeDataset(fmi, dataset);
                    }
                    log.debug("Updated DICOM file: {}", dicomFile.getFileName());
                }

            } catch (Exception e) {
                log.error("Failed to apply tag modifications to {}: {}", dicomFile.getFileName(), e.getMessage());
                throw e;
            }
        }
    }

    /**
     * Parse a tag specification like "0010,0010" or "PatientName" to an integer tag.
     */
    private int parseTag(String tagSpec) {
        if (tagSpec == null || tagSpec.isEmpty()) {
            return -1;
        }

        // Try hex format: "0010,0010"
        if (tagSpec.contains(",")) {
            try {
                String[] parts = tagSpec.split(",");
                return Integer.parseInt(parts[0].trim(), 16) << 16 |
                        Integer.parseInt(parts[1].trim(), 16);
            } catch (Exception e) {
                return -1;
            }
        }

        // Try common tag name resolution
        return resolveTagByName(tagSpec);
    }

    /**
     * Guess the VR for common tags.
     */
    private VR guessVR(int tag) {
        // Common string VRs
        switch (tag) {
            case Tag.PatientName:
            case Tag.ReferringPhysicianName:
            case Tag.PerformingPhysicianName:
                return VR.PN;
            case Tag.PatientID:
            case Tag.AccessionNumber:
            case Tag.StudyID:
                return VR.LO;
            case Tag.PatientBirthDate:
            case Tag.StudyDate:
            case Tag.SeriesDate:
                return VR.DA;
            case Tag.StudyTime:
            case Tag.SeriesTime:
                return VR.TM;
            case Tag.InstitutionName:
            case Tag.StationName:
            case Tag.Manufacturer:
                return VR.LO;
            default:
                return VR.LO; // Default to Long String
        }
    }

    /**
     * Hash a value for pseudo-anonymization.
     */
    private String hashValue(String value) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            // Return first 16 chars of hex representation
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString().toUpperCase();
        } catch (Exception e) {
            log.warn("Failed to hash value, using simple replacement: {}", e.getMessage());
            return "ANON_" + value.hashCode();
        }
    }

    // Helper methods

    private Attributes readStudyAttributes(Path studyDir) {
        try {
            // Read first DICOM file to get study attributes
            Optional<Path> firstFile = Files.list(studyDir)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".dcm"))
                    .findFirst();

            if (firstFile.isEmpty()) return null;

            try (DicomInputStream dis = new DicomInputStream(firstFile.get().toFile())) {
                return dis.readDataset();
            }
        } catch (Exception e) {
            log.error("Failed to read DICOM attributes: {}", e.getMessage());
            return null;
        }
    }

    private String getTagValue(Attributes attrs, String tagSpec) {
        if (attrs == null || tagSpec == null) return null;

        // Parse tag specification (e.g., "0008,0060" or "Modality")
        try {
            int tag;
            if (tagSpec.contains(",")) {
                String[] parts = tagSpec.split(",");
                tag = Integer.parseInt(parts[0].trim(), 16) << 16 |
                        Integer.parseInt(parts[1].trim(), 16);
            } else {
                // Try to resolve common tag names
                tag = resolveTagByName(tagSpec);
            }
            return attrs.getString(tag);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Resolve common DICOM tag names to tag numbers.
     */
    private int resolveTagByName(String name) {
        switch (name.toLowerCase()) {
            case "patientid": return Tag.PatientID;
            case "patientname": return Tag.PatientName;
            case "patientsname": return Tag.PatientName;
            case "studyinstanceuid": return Tag.StudyInstanceUID;
            case "seriesinstanceuid": return Tag.SeriesInstanceUID;
            case "sopinstanceuid": return Tag.SOPInstanceUID;
            case "sopclassuid": return Tag.SOPClassUID;
            case "modality": return Tag.Modality;
            case "studydate": return Tag.StudyDate;
            case "studytime": return Tag.StudyTime;
            case "seriesdate": return Tag.SeriesDate;
            case "seriestime": return Tag.SeriesTime;
            case "studydescription": return Tag.StudyDescription;
            case "seriesdescription": return Tag.SeriesDescription;
            case "accessionnumber": return Tag.AccessionNumber;
            case "institutionname": return Tag.InstitutionName;
            case "referringphysicianname": return Tag.ReferringPhysicianName;
            case "bodypartexamined": return Tag.BodyPartExamined;
            case "seriesnumber": return Tag.SeriesNumber;
            case "instancenumber": return Tag.InstanceNumber;
            case "manufacturer": return Tag.Manufacturer;
            case "manufacturermodelname": return Tag.ManufacturerModelName;
            case "stationname": return Tag.StationName;
            default:
                throw new IllegalArgumentException("Unknown tag name: " + name);
        }
    }

    private String expandPattern(String pattern, Attributes attrs) {
        if (pattern == null) return "";

        String result = pattern;
        // Replace {TagName} patterns with actual values
        for (String placeholder : extractPlaceholders(pattern)) {
            String value = getTagValue(attrs, placeholder);
            if (value == null) value = "UNKNOWN";
            // Sanitize for filesystem
            value = value.replaceAll("[^a-zA-Z0-9_-]", "_");
            result = result.replace("{" + placeholder + "}", value);
        }
        return result;
    }

    private List<String> extractPlaceholders(String pattern) {
        List<String> placeholders = new ArrayList<>();
        int start = 0;
        while ((start = pattern.indexOf("{", start)) != -1) {
            int end = pattern.indexOf("}", start);
            if (end != -1) {
                placeholders.add(pattern.substring(start + 1, end));
                start = end + 1;
            } else {
                break;
            }
        }
        return placeholders;
    }

    private Path prepareProcessingDir(DicomReceiver.ReceivedStudy study,
                                       AppConfig.RouteConfig route) throws IOException {
        Path processingBase = Paths.get(config.getReceiver().getBaseDir(),
                route.getAeTitle(), "processing");
        Path processingDir = processingBase.resolve("study_" + study.getStudyUid() +
                "_" + System.currentTimeMillis());
        Files.createDirectories(processingDir);
        return processingDir;
    }

    private void copyFilesToProcessing(Path sourceDir, Path targetDir) throws IOException {
        Files.list(sourceDir)
                .filter(Files::isRegularFile)
                .forEach(file -> {
                    try {
                        Files.copy(file, targetDir.resolve(file.getFileName()),
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        log.warn("Failed to copy file: {}", file);
                    }
                });
    }

    private void moveToCompleted(DicomReceiver.ReceivedStudy study,
                                  AppConfig.RouteConfig route) {
        try {
            Path completedDir = Paths.get(config.getReceiver().getBaseDir(),
                    route.getAeTitle(), "completed",
                    LocalDateTime.now().toLocalDate().toString());
            Files.createDirectories(completedDir);

            Path target = completedDir.resolve(study.getPath().getFileName());
            Files.move(study.getPath(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("Failed to move to completed: {}", e.getMessage());
        }
    }

    private void moveToFailed(DicomReceiver.ReceivedStudy study,
                               AppConfig.RouteConfig route, String reason) {
        try {
            Path failedDir = Paths.get(config.getReceiver().getBaseDir(),
                    route.getAeTitle(), "failed",
                    LocalDateTime.now().toLocalDate().toString());
            Files.createDirectories(failedDir);

            Path target = failedDir.resolve(study.getPath().getFileName());
            Files.move(study.getPath(), target, StandardCopyOption.REPLACE_EXISTING);

            // Write failure reason
            Files.writeString(target.resolve("failure_reason.txt"), reason);
        } catch (IOException e) {
            log.warn("Failed to move to failed: {}", e.getMessage());
        }
    }

    private Path createZip(List<File> files) throws IOException {
        Path zipFile = Files.createTempFile("dicom_", ".zip");

        try (java.util.zip.ZipOutputStream zos =
                     new java.util.zip.ZipOutputStream(Files.newOutputStream(zipFile))) {
            for (File file : files) {
                zos.putNextEntry(new java.util.zip.ZipEntry(file.getName()));
                Files.copy(file.toPath(), zos);
                zos.closeEntry();
            }
        }

        return zipFile;
    }

    private Path findDicomEditJar() {
        String[] searchPaths = {"libs", "../lib", "."};
        for (String basePath : searchPaths) {
            try {
                Path dir = Paths.get(basePath);
                if (Files.isDirectory(dir)) {
                    Optional<Path> found = Files.list(dir)
                            .filter(p -> p.getFileName().toString().startsWith("dicom-edit"))
                            .filter(p -> p.getFileName().toString().endsWith(".jar"))
                            .findFirst();
                    if (found.isPresent()) {
                        return found.get().toAbsolutePath();
                    }
                }
            } catch (IOException e) {
                // Continue searching
            }
        }
        return null;
    }

    private void deleteDirectory(Path dir) {
        try {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            // Ignore
                        }
                    });
        } catch (IOException e) {
            // Ignore
        }
    }

    private void scheduleRetry(DicomReceiver.ReceivedStudy study,
                                AppConfig.RouteConfig route, int attempt) {
        // Schedule retry with exponential backoff
        long delay = (long) Math.pow(2, attempt) * 60;  // 1, 2, 4, 8... minutes
        retryScheduler.schedule(() -> {
            try {
                processStudy(study, route);
            } catch (Exception e) {
                log.error("Retry failed: {}", e.getMessage());
            }
        }, delay, TimeUnit.SECONDS);
    }

    private void scheduleDestinationRetry(Path sourceDir, Attributes attrs,
                                           AppConfig.RouteDestination routeDest,
                                           AppConfig.RouteConfig route, int attempt) {
        if (attempt >= routeDest.getRetryCount()) {
            log.warn("Max retries exceeded for destination: {}", routeDest.getDestination());
            return;
        }

        long delay = routeDest.getRetryDelaySeconds();
        retryScheduler.schedule(() -> {
            try {
                DestinationForwardResult result = forwardToDestination(
                        sourceDir, attrs, routeDest, route);
                if (!result.isSuccess()) {
                    scheduleDestinationRetry(sourceDir, attrs, routeDest, route, attempt + 1);
                }
            } catch (Exception e) {
                log.error("Retry failed: {}", e.getMessage());
                scheduleDestinationRetry(sourceDir, attrs, routeDest, route, attempt + 1);
            }
        }, delay, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        // Shutdown all executors
        for (ExecutorService executor : routeExecutors.values()) {
            executor.shutdown();
            try {
                executor.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        routeExecutors.clear();

        retryScheduler.shutdown();
    }

    // Result classes

    public static class ForwardResult {
        private String studyUid;
        private String status;
        private int totalDestinations;
        private int successCount;
        private int failedCount;
        private String message;

        public static ForwardResult failed(String message) {
            ForwardResult r = new ForwardResult();
            r.status = "failed";
            r.message = message;
            return r;
        }

        public static ForwardResult queued(String message) {
            ForwardResult r = new ForwardResult();
            r.status = "queued";
            r.message = message;
            return r;
        }

        public static ForwardResult filtered(String message) {
            ForwardResult r = new ForwardResult();
            r.status = "filtered";
            r.message = message;
            return r;
        }

        public String getStudyUid() { return studyUid; }
        public void setStudyUid(String studyUid) { this.studyUid = studyUid; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public int getTotalDestinations() { return totalDestinations; }
        public void setTotalDestinations(int totalDestinations) { this.totalDestinations = totalDestinations; }

        public int getSuccessCount() { return successCount; }
        public void incrementSuccess() { this.successCount++; }

        public int getFailedCount() { return failedCount; }
        public void incrementFailed() { this.failedCount++; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }

    public static class DestinationForwardResult {
        private boolean success;
        private String message;
        private long durationMs;
        private int filesTransferred;

        public DestinationForwardResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }

        public long getDurationMs() { return durationMs; }
        public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

        public int getFilesTransferred() { return filesTransferred; }
        public void setFilesTransferred(int filesTransferred) { this.filesTransferred = filesTransferred; }
    }

    private static class ValidationResult {
        private final boolean valid;
        private final String action;
        private final String message;

        public ValidationResult(boolean valid, String action, String message) {
            this.valid = valid;
            this.action = action;
            this.message = message;
        }

        public boolean isValid() { return valid; }
        public String getAction() { return action; }
        public String getMessage() { return message; }
    }

    /**
     * Simple rate limiter using token bucket algorithm.
     */
    private static class RateLimiter {
        private final int maxPerMinute;
        private final Queue<Long> timestamps = new ConcurrentLinkedQueue<>();

        public RateLimiter(int maxPerMinute) {
            this.maxPerMinute = maxPerMinute;
        }

        public synchronized boolean tryAcquire() {
            long now = System.currentTimeMillis();
            long oneMinuteAgo = now - 60000;

            // Remove old timestamps
            while (!timestamps.isEmpty() && timestamps.peek() < oneMinuteAgo) {
                timestamps.poll();
            }

            if (timestamps.size() < maxPerMinute) {
                timestamps.add(now);
                return true;
            }
            return false;
        }
    }
}
