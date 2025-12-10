/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router;

import io.xnatworks.router.anon.ScriptLibrary;
import io.xnatworks.router.config.AppConfig;
import io.xnatworks.router.dicom.DicomClient;
import io.xnatworks.router.dicom.DicomReceiver;
import io.xnatworks.router.routing.DestinationManager;
import io.xnatworks.router.tracking.TransferTracker;
import io.xnatworks.router.tracking.StorageCleanupService;
import io.xnatworks.router.xnat.XnatClient;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * XNAT DICOM Router - Main Application
 *
 * A general-purpose DICOM routing application that can:
 * - Receive DICOM via C-STORE
 * - Route to multiple destinations (XNAT, DICOM AE, File System)
 * - Apply anonymization scripts from a configurable library
 * - Track and log all transfers
 * - Provide REST API and web UI for administration
 */
@Command(name = "dicom-router",
        mixinStandardHelpOptions = true,
        version = "2.0.0",
        description = "XNAT DICOM Router - Route DICOM to multiple destinations",
        subcommands = {
                DicomRouter.StartCommand.class,
                DicomRouter.StatusCommand.class,
                DicomRouter.RoutesCommand.class,
                DicomRouter.DestinationsCommand.class,
                DicomRouter.ScriptsCommand.class,
                DicomRouter.QueryCommand.class,
                DicomRouter.HistoryCommand.class
        })
public class DicomRouter implements Callable<Integer> {
    private static final Logger log = LoggerFactory.getLogger(DicomRouter.class);

    @Option(names = {"-c", "--config"}, description = "Config file path", defaultValue = "config.yaml")
    protected File configFile;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new DicomRouter()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    // ========================================================================
    // START COMMAND - Start the router
    // ========================================================================

    @Command(name = "start", description = "Start DICOM router")
    static class StartCommand implements Callable<Integer> {

        @ParentCommand
        private DicomRouter parent;

        @Option(names = {"--routes"}, description = "Specific routes to start (comma-separated AE titles)")
        private String routes;

        @Option(names = {"--no-admin"}, description = "Disable admin server completely (no API, no UI)")
        private boolean noAdmin;

        @Option(names = {"--headless"}, description = "API only mode - no web UI, just REST API")
        private boolean headless;

        @Option(names = {"--admin-port"}, description = "Admin server port (overrides config file)")
        private Integer adminPort;

        @Override
        public Integer call() throws Exception {
            AppConfig config = AppConfig.load(parent.configFile);

            // Use CLI admin port if specified, otherwise use config file value
            int effectiveAdminPort = adminPort != null ? adminPort : config.getAdminPort();

            printBanner();
            log.info("Starting DICOM Router...");
            log.info("Config: {}", parent.configFile.getAbsolutePath());

            // Initialize components
            Path baseDir = Paths.get(config.getReceiver().getBaseDir());
            Files.createDirectories(baseDir);

            ScriptLibrary scriptLibrary = new ScriptLibrary(baseDir.resolve("scripts"));
            TransferTracker transferTracker = new TransferTracker(baseDir);
            DestinationManager destinationManager = new DestinationManager(config);

            // Start health checks
            destinationManager.startHealthChecks();

            // Start storage cleanup service (runs daily to clean up old completed/failed folders)
            StorageCleanupService cleanupService = new StorageCleanupService(config);
            cleanupService.start();

            // Determine which routes to start
            List<AppConfig.RouteConfig> routesToStart = new ArrayList<>();
            if (routes != null && !routes.isEmpty()) {
                for (String aeTitle : routes.split(",")) {
                    AppConfig.RouteConfig route = config.findRouteByAeTitle(aeTitle.trim());
                    if (route != null && route.isEnabled()) {
                        routesToStart.add(route);
                    } else {
                        log.warn("Route not found or disabled: {}", aeTitle);
                    }
                }
            } else {
                for (AppConfig.RouteConfig route : config.getRoutes()) {
                    if (route.isEnabled()) {
                        routesToStart.add(route);
                    }
                }
            }

            if (routesToStart.isEmpty()) {
                log.error("No routes to start. Check your configuration.");
                return 1;
            }

            // Start receivers
            List<DicomReceiver> receivers = new ArrayList<>();
            for (AppConfig.RouteConfig route : routesToStart) {
                DicomReceiver receiver = new DicomReceiver(
                        route,
                        config.getReceiver().getBaseDir(),
                        study -> processStudy(study, route, config, scriptLibrary,
                                destinationManager, transferTracker)
                );
                receiver.start();
                receivers.add(receiver);

                log.info("Started route: {} on port {} ({} destinations)",
                        route.getAeTitle(), route.getPort(), route.getDestinations().size());
            }

            // Start admin server if enabled
            io.xnatworks.router.api.AdminServer adminServer = null;
            if (!noAdmin) {
                adminServer = new io.xnatworks.router.api.AdminServer(
                        effectiveAdminPort,
                        "localhost",
                        config,
                        destinationManager,
                        transferTracker,
                        scriptLibrary,
                        headless  // headless mode = API only, no UI
                );
                adminServer.start();
            }

            // Print status
            System.out.println();
            System.out.println("=========================================================");
            System.out.println("  DICOM Router Started");
            System.out.println("=========================================================");
            System.out.println();
            System.out.println("Active Routes:");
            for (AppConfig.RouteConfig route : routesToStart) {
                System.out.printf("  %-20s port %-6d -> %d destination(s)%n",
                        route.getAeTitle(), route.getPort(), route.getDestinations().size());
            }
            System.out.println();
            System.out.println("Destinations:");
            for (Map.Entry<String, DestinationManager.DestinationHealth> entry :
                    destinationManager.getAllHealth().entrySet()) {
                String status = entry.getValue().isAvailable() ? "✓ AVAILABLE" : "✗ UNAVAILABLE";
                System.out.printf("  %-20s [%s] %s%n",
                        entry.getKey(), entry.getValue().getType(), status);
            }
            System.out.println();
            if (!noAdmin) {
                if (headless) {
                    System.out.printf("Admin API: http://localhost:%d/api (headless mode)%n", effectiveAdminPort);
                } else {
                    System.out.printf("Admin UI: http://localhost:%d%n", effectiveAdminPort);
                }
            }
            System.out.println("Press Ctrl+C to stop.");
            System.out.println("=========================================================");

            // Add shutdown hook
            final io.xnatworks.router.api.AdminServer finalAdminServer = adminServer;
            final StorageCleanupService finalCleanupService = cleanupService;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down...");
                receivers.forEach(DicomReceiver::stop);
                destinationManager.close();
                finalCleanupService.close();
                if (finalAdminServer != null) {
                    try {
                        finalAdminServer.stop();
                    } catch (Exception e) {
                        log.warn("Error stopping admin server: {}", e.getMessage());
                    }
                }
            }));

            // Keep running
            Thread.currentThread().join();
            return 0;
        }

        private void processStudy(DicomReceiver.ReceivedStudy study,
                                  AppConfig.RouteConfig route,
                                  AppConfig config,
                                  ScriptLibrary scriptLibrary,
                                  DestinationManager destinationManager,
                                  TransferTracker transferTracker) {
            log.info("[{}] Processing study: {} ({} files)",
                    route.getAeTitle(), study.getStudyUid(), study.getFileCount());

            // Create transfer record
            TransferTracker.TransferRecord transfer = transferTracker.createTransfer(
                    route.getAeTitle(),
                    study.getStudyUid(),
                    study.getCallingAeTitle(),
                    study.getFileCount(),
                    study.getTotalSize()
            );
            String transferId = transfer.getId();

            // Mark as processing
            transferTracker.startProcessing(transferId);

            // Process each destination
            boolean allSuccess = true;
            boolean anySuccess = false;

            for (AppConfig.RouteDestination routeDest : route.getDestinations()) {
                if (!routeDest.isEnabled()) {
                    log.debug("[{}] Skipping disabled destination: {}", route.getAeTitle(), routeDest.getDestination());
                    continue;
                }

                String destName = routeDest.getDestination();
                AppConfig.Destination dest = config.getDestination(destName);
                if (dest == null) {
                    log.error("[{}] Destination not found: {}", route.getAeTitle(), destName);
                    transferTracker.updateDestinationResult(transferId, destName,
                            TransferTracker.DestinationStatus.FAILED, "Destination not configured", 0, 0);
                    allSuccess = false;
                    continue;
                }

                if (!dest.isEnabled()) {
                    log.debug("[{}] Skipping disabled destination: {}", route.getAeTitle(), destName);
                    continue;
                }

                log.info("[{}] Forwarding to destination: {} (type: {})", route.getAeTitle(), destName, dest.getType());

                try {
                    long startTime = System.currentTimeMillis();
                    int filesTransferred = 0;
                    String message = null;
                    boolean success = false;

                    if (dest instanceof AppConfig.XnatDestination) {
                        // Forward to XNAT
                        XnatClient client = destinationManager.getXnatClient(destName);
                        if (client == null) {
                            throw new RuntimeException("No XNAT client available for destination: " + destName);
                        }

                        // Check availability
                        if (!destinationManager.isAvailable(destName)) {
                            throw new RuntimeException("XNAT destination unavailable: " + destName);
                        }

                        // Create ZIP file from DICOM files
                        File zipFile = createZipFromStudy(study, routeDest.isAnonymize(), scriptLibrary, routeDest.getEffectiveAnonScript());

                        try {
                            // Get project, subject, session info
                            String projectId = routeDest.getProjectId();
                            if (projectId == null || projectId.isEmpty()) {
                                projectId = extractProjectId(study);
                            }

                            String subjectId = generateSubjectId(study, routeDest.getSubjectPrefix());
                            String sessionLabel = generateSessionLabel(study, routeDest.getSessionPrefix());

                            log.info("[{}] Uploading to XNAT {} - Project: {}, Subject: {}, Session: {}, AutoArchive: {}",
                                    route.getAeTitle(), destName, projectId, subjectId, sessionLabel, routeDest.isAutoArchive());

                            // Upload to XNAT with retry settings from config
                            XnatClient.UploadResult result = client.uploadWithRetry(
                                    zipFile, projectId, subjectId, sessionLabel,
                                    routeDest.isAutoArchive(),
                                    routeDest.getRetryCount(),
                                    routeDest.getRetryDelaySeconds() * 1000L
                            );

                            success = result.isSuccess();
                            filesTransferred = study.getFileCount();
                            message = success ? "Uploaded successfully" : result.getErrorMessage();

                            if (success) {
                                log.info("[{}] Successfully uploaded {} files to XNAT {} ({}ms, {:.1f} MB/s)",
                                        route.getAeTitle(), filesTransferred, destName,
                                        result.getDurationMs(), result.getSpeedMBps());
                            } else {
                                log.error("[{}] Failed to upload to XNAT {}: {}", route.getAeTitle(), destName, message);
                            }
                        } finally {
                            // Clean up ZIP file
                            if (zipFile.exists()) {
                                zipFile.delete();
                            }
                        }

                    } else if (dest instanceof AppConfig.DicomAeDestination) {
                        // Forward to DICOM AE
                        DicomClient client = destinationManager.getDicomClient(destName);
                        if (client == null) {
                            throw new RuntimeException("No DICOM client available for destination: " + destName);
                        }

                        // Check availability
                        if (!destinationManager.isAvailable(destName)) {
                            throw new RuntimeException("DICOM destination unavailable: " + destName);
                        }

                        // Send files via C-STORE
                        List<File> files = study.getFiles();
                        DicomClient.StoreResult storeResult = client.store(files);

                        success = storeResult.isSuccess();
                        filesTransferred = storeResult.getSuccessCount();
                        message = success ? "Sent all files" : "Sent " + storeResult.getSuccessCount() + "/" + files.size() + " files";

                        log.info("[{}] Sent {}/{} files to DICOM AE {}",
                                route.getAeTitle(), storeResult.getSuccessCount(), files.size(), destName);

                    } else if (dest instanceof AppConfig.FileDestination) {
                        // Forward to file system
                        AppConfig.FileDestination fileDest = (AppConfig.FileDestination) dest;
                        String subDir = generateFileSubDir(study, fileDest);

                        DestinationManager.ForwardResult result = destinationManager.forwardToFile(
                                destName, study.getFiles(), subDir);

                        success = result.isSuccess();
                        filesTransferred = result.getSuccessCount();
                        message = success ? "Copied all files" : result.getErrorMessage();

                        log.info("[{}] Copied {}/{} files to file destination {}",
                                route.getAeTitle(), filesTransferred, study.getFileCount(), destName);
                    }

                    long duration = System.currentTimeMillis() - startTime;
                    TransferTracker.DestinationStatus destStatus = success ?
                            TransferTracker.DestinationStatus.SUCCESS :
                            TransferTracker.DestinationStatus.FAILED;
                    transferTracker.updateDestinationResult(transferId, destName, destStatus, message, duration, filesTransferred);

                    if (success) {
                        anySuccess = true;
                    } else {
                        allSuccess = false;
                    }

                } catch (Exception e) {
                    log.error("[{}] Error forwarding to {}: {}", route.getAeTitle(), destName, e.getMessage(), e);
                    transferTracker.updateDestinationResult(transferId, destName,
                            TransferTracker.DestinationStatus.FAILED, e.getMessage(), 0, 0);
                    allSuccess = false;
                }
            }

            // Move study based on outcome
            // Note: TransferTracker automatically updates status when all destinations report
            if (allSuccess || anySuccess) {
                moveStudyToCompleted(study, route);
                if (allSuccess) {
                    log.info("[{}] Transfer {} completed successfully", route.getAeTitle(), transferId);
                } else {
                    log.warn("[{}] Transfer {} partially completed", route.getAeTitle(), transferId);
                }
            } else {
                moveStudyToFailed(study, route);
                log.error("[{}] Transfer {} failed", route.getAeTitle(), transferId);
            }
        }

        private File createZipFromStudy(DicomReceiver.ReceivedStudy study, boolean anonymize,
                                         ScriptLibrary scriptLibrary, String anonScriptName) throws IOException {
            Path tempZip = Files.createTempFile("dicom_upload_", ".zip");

            try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(tempZip))) {
                for (File file : study.getFiles()) {
                    java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(file.getName());
                    zos.putNextEntry(entry);
                    Files.copy(file.toPath(), zos);
                    zos.closeEntry();
                }
            }

            return tempZip.toFile();
        }

        private String extractProjectId(DicomReceiver.ReceivedStudy study) {
            // Try to extract from DICOM metadata, or use default
            if (study.getFiles().isEmpty()) {
                return "UNKNOWN";
            }
            try {
                File firstFile = study.getFiles().get(0);
                org.dcm4che3.io.DicomInputStream dis = new org.dcm4che3.io.DicomInputStream(firstFile);
                Attributes attrs = dis.readDataset();
                dis.close();

                // Try InstitutionName or use default
                String institution = attrs.getString(Tag.InstitutionName, "");
                if (!institution.isEmpty()) {
                    return institution.replaceAll("[^a-zA-Z0-9_-]", "_");
                }
            } catch (Exception e) {
                log.debug("Could not extract project ID from DICOM: {}", e.getMessage());
            }
            return "UPLOADED";
        }

        private String generateSubjectId(DicomReceiver.ReceivedStudy study, String prefix) {
            if (prefix == null) prefix = "SUBJ";

            if (study.getFiles().isEmpty()) {
                return prefix + "_" + study.getStudyUid().substring(Math.max(0, study.getStudyUid().length() - 8));
            }

            try {
                File firstFile = study.getFiles().get(0);
                org.dcm4che3.io.DicomInputStream dis = new org.dcm4che3.io.DicomInputStream(firstFile);
                Attributes attrs = dis.readDataset();
                dis.close();

                String patientId = attrs.getString(Tag.PatientID, "");
                if (!patientId.isEmpty()) {
                    return prefix + patientId;
                }
            } catch (Exception e) {
                log.debug("Could not extract patient ID from DICOM: {}", e.getMessage());
            }

            return prefix + "_" + study.getStudyUid().substring(Math.max(0, study.getStudyUid().length() - 8));
        }

        private String generateSessionLabel(DicomReceiver.ReceivedStudy study, String prefix) {
            if (prefix == null) prefix = "SESSION";

            // Use study date + study UID suffix
            String datePart = LocalDate.now().toString().replace("-", "");
            String uidSuffix = study.getStudyUid().substring(Math.max(0, study.getStudyUid().length() - 8));

            return prefix + datePart + "_" + uidSuffix;
        }

        private String generateFileSubDir(DicomReceiver.ReceivedStudy study, AppConfig.FileDestination fileDest) {
            // Generate subdirectory based on pattern, or use Study UID
            String pattern = fileDest.getDirectoryPattern();
            if (pattern == null || pattern.isEmpty()) {
                return study.getStudyUid();
            }

            // Simple pattern replacement
            return pattern
                    .replace("{StudyUID}", study.getStudyUid())
                    .replace("{StudyDate}", LocalDate.now().toString())
                    .replace("{CallingAE}", study.getCallingAeTitle());
        }

        private void moveStudyToCompleted(DicomReceiver.ReceivedStudy study, AppConfig.RouteConfig route) {
            try {
                Path sourceDir = study.getStudyDir();
                Path completedDir = sourceDir.getParent().getParent().resolve("completed").resolve(sourceDir.getFileName());
                Files.createDirectories(completedDir.getParent());
                Files.move(sourceDir, completedDir, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                log.debug("[{}] Moved study to completed: {}", route.getAeTitle(), completedDir);
            } catch (IOException e) {
                log.warn("[{}] Failed to move study to completed: {}", route.getAeTitle(), e.getMessage());
            }
        }

        private void moveStudyToFailed(DicomReceiver.ReceivedStudy study, AppConfig.RouteConfig route) {
            try {
                Path sourceDir = study.getStudyDir();
                Path failedDir = sourceDir.getParent().getParent().resolve("failed").resolve(sourceDir.getFileName());
                Files.createDirectories(failedDir.getParent());
                Files.move(sourceDir, failedDir, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                log.debug("[{}] Moved study to failed: {}", route.getAeTitle(), failedDir);
            } catch (IOException e) {
                log.warn("[{}] Failed to move study to failed: {}", route.getAeTitle(), e.getMessage());
            }
        }

        private void printBanner() {
            System.out.println();
            System.out.println("╔═══════════════════════════════════════════════════════╗");
            System.out.println("║                                                       ║");
            System.out.println("║             XNAT DICOM Router v2.0.0                  ║");
            System.out.println("║         Copyright © 2025 XNATWorks.                  ║");
            System.out.println("║                                                       ║");
            System.out.println("╚═══════════════════════════════════════════════════════╝");
            System.out.println();
        }
    }

    // ========================================================================
    // STATUS COMMAND - Show system status
    // ========================================================================

    @Command(name = "status", description = "Show router status")
    static class StatusCommand implements Callable<Integer> {

        @ParentCommand
        private DicomRouter parent;

        @Override
        public Integer call() throws Exception {
            AppConfig config = AppConfig.load(parent.configFile);

            System.out.println();
            System.out.println("=========================================================");
            System.out.println("  DICOM Router Status");
            System.out.println("=========================================================");
            System.out.println();

            // Check destinations
            System.out.println("Destinations:");
            System.out.println("─────────────────────────────────────────────────────────");
            System.out.printf("%-20s %-10s %-30s %-10s%n", "NAME", "TYPE", "URL", "STATUS");
            System.out.println("─────────────────────────────────────────────────────────");

            DestinationManager destManager = new DestinationManager(config);
            destManager.checkAllDestinations();

            for (Map.Entry<String, DestinationManager.DestinationHealth> entry :
                    destManager.getAllHealth().entrySet()) {
                DestinationManager.DestinationHealth health = entry.getValue();
                String status = health.isAvailable() ?
                        "\u001B[32mAVAILABLE\u001B[0m" :
                        "\u001B[31mUNAVAILABLE\u001B[0m";
                String url = health.getUrl();
                if (url != null && url.length() > 28) {
                    url = url.substring(0, 26) + "..";
                }
                System.out.printf("%-20s %-10s %-30s %s%n",
                        entry.getKey(), health.getType(), url != null ? url : "-", status);
            }

            destManager.close();

            // Show routes
            System.out.println();
            System.out.println("Configured Routes:");
            System.out.println("─────────────────────────────────────────────────────────");
            System.out.printf("%-20s %-8s %-8s %-20s%n", "AE TITLE", "PORT", "ENABLED", "DESTINATIONS");
            System.out.println("─────────────────────────────────────────────────────────");

            for (AppConfig.RouteConfig route : config.getRoutes()) {
                String enabled = route.isEnabled() ? "Yes" : "No";
                StringBuilder dests = new StringBuilder();
                for (AppConfig.RouteDestination dest : route.getDestinations()) {
                    if (dests.length() > 0) dests.append(", ");
                    dests.append(dest.getDestination());
                }
                System.out.printf("%-20s %-8d %-8s %-20s%n",
                        route.getAeTitle(), route.getPort(), enabled, dests.toString());
            }

            System.out.println();
            return 0;
        }
    }

    // ========================================================================
    // ROUTES COMMAND - Manage routes
    // ========================================================================

    @Command(name = "routes", description = "Manage routes",
            subcommands = {
                    RoutesCommand.ListRoutes.class,
                    RoutesCommand.ShowRoute.class
            })
    static class RoutesCommand implements Callable<Integer> {

        @Override
        public Integer call() {
            CommandLine.usage(this, System.out);
            return 0;
        }

        @Command(name = "list", description = "List all routes")
        static class ListRoutes implements Callable<Integer> {
            @ParentCommand
            private RoutesCommand parent;

            @Option(names = {"-c", "--config"}, defaultValue = "config.yaml")
            private File configFile;

            @Override
            public Integer call() throws Exception {
                AppConfig config = AppConfig.load(configFile);

                System.out.println();
                System.out.printf("%-20s %-8s %-8s %-6s %-30s%n",
                        "AE TITLE", "PORT", "THREADS", "DESTS", "DESCRIPTION");
                System.out.println("─".repeat(80));

                for (AppConfig.RouteConfig route : config.getRoutes()) {
                    String desc = route.getDescription();
                    if (desc != null && desc.length() > 28) {
                        desc = desc.substring(0, 26) + "..";
                    }
                    System.out.printf("%-20s %-8d %-8d %-6d %-30s%n",
                            route.getAeTitle(),
                            route.getPort(),
                            route.getWorkerThreads(),
                            route.getDestinations().size(),
                            desc != null ? desc : "");
                }

                return 0;
            }
        }

        @Command(name = "show", description = "Show route details")
        static class ShowRoute implements Callable<Integer> {
            @ParentCommand
            private RoutesCommand parent;

            @Option(names = {"-c", "--config"}, defaultValue = "config.yaml")
            private File configFile;

            @Parameters(index = "0", description = "Route AE Title")
            private String aeTitle;

            @Override
            public Integer call() throws Exception {
                AppConfig config = AppConfig.load(configFile);
                AppConfig.RouteConfig route = config.findRouteByAeTitle(aeTitle);

                if (route == null) {
                    System.err.println("Route not found: " + aeTitle);
                    return 1;
                }

                System.out.println();
                System.out.println("Route: " + route.getAeTitle());
                System.out.println("─".repeat(50));
                System.out.println("Port:                " + route.getPort());
                System.out.println("Enabled:             " + route.isEnabled());
                System.out.println("Worker Threads:      " + route.getWorkerThreads());
                System.out.println("Max Transfers:       " + route.getMaxConcurrentTransfers());
                System.out.println("Study Timeout:       " + route.getStudyTimeoutSeconds() + "s");
                System.out.println("Description:         " + route.getDescription());
                System.out.println();
                System.out.println("Destinations:");

                for (AppConfig.RouteDestination dest : route.getDestinations()) {
                    System.out.println("  - " + dest.getDestination());
                    System.out.println("    Anonymize:   " + dest.isAnonymize());
                    if (dest.getAnonScript() != null) {
                        System.out.println("    Script:      " + dest.getAnonScript());
                    }
                    if (dest.getProjectId() != null) {
                        System.out.println("    Project ID:  " + dest.getProjectId());
                    }
                    System.out.println("    Priority:    " + dest.getPriority());
                    System.out.println("    Retry Count: " + dest.getRetryCount());
                }

                return 0;
            }
        }
    }

    // ========================================================================
    // DESTINATIONS COMMAND - Manage destinations
    // ========================================================================

    @Command(name = "destinations", description = "Manage destinations",
            subcommands = {
                    DestinationsCommand.ListDestinations.class,
                    DestinationsCommand.TestDestination.class
            })
    static class DestinationsCommand implements Callable<Integer> {

        @Override
        public Integer call() {
            CommandLine.usage(this, System.out);
            return 0;
        }

        @Command(name = "list", description = "List all destinations")
        static class ListDestinations implements Callable<Integer> {
            @Option(names = {"-c", "--config"}, defaultValue = "config.yaml")
            private File configFile;

            @Override
            public Integer call() throws Exception {
                AppConfig config = AppConfig.load(configFile);

                System.out.println();
                System.out.printf("%-20s %-10s %-40s %-10s%n", "NAME", "TYPE", "URL/PATH", "ENABLED");
                System.out.println("─".repeat(85));

                for (Map.Entry<String, AppConfig.Destination> entry : config.getDestinations().entrySet()) {
                    AppConfig.Destination dest = entry.getValue();
                    String url = "";
                    if (dest instanceof AppConfig.XnatDestination) {
                        url = ((AppConfig.XnatDestination) dest).getUrl();
                    } else if (dest instanceof AppConfig.DicomAeDestination) {
                        AppConfig.DicomAeDestination d = (AppConfig.DicomAeDestination) dest;
                        url = d.getAeTitle() + "@" + d.getHost() + ":" + d.getPort();
                    } else if (dest instanceof AppConfig.FileDestination) {
                        url = ((AppConfig.FileDestination) dest).getPath();
                    }
                    if (url.length() > 38) {
                        url = url.substring(0, 36) + "..";
                    }

                    System.out.printf("%-20s %-10s %-40s %-10s%n",
                            entry.getKey(), dest.getType(), url, dest.isEnabled() ? "Yes" : "No");
                }

                return 0;
            }
        }

        @Command(name = "test", description = "Test destination connectivity")
        static class TestDestination implements Callable<Integer> {
            @Option(names = {"-c", "--config"}, defaultValue = "config.yaml")
            private File configFile;

            @Parameters(index = "0", description = "Destination name")
            private String name;

            @Override
            public Integer call() throws Exception {
                AppConfig config = AppConfig.load(configFile);
                DestinationManager destManager = new DestinationManager(config);

                System.out.println("Testing destination: " + name);
                boolean available = destManager.checkDestination(name);

                if (available) {
                    System.out.println("\u001B[32m✓ Destination is AVAILABLE\u001B[0m");
                } else {
                    System.out.println("\u001B[31m✗ Destination is UNAVAILABLE\u001B[0m");
                }

                destManager.close();
                return available ? 0 : 1;
            }
        }
    }

    // ========================================================================
    // SCRIPTS COMMAND - Manage anonymization scripts
    // ========================================================================

    @Command(name = "scripts", description = "Manage anonymization scripts",
            subcommands = {
                    ScriptsCommand.ListScripts.class,
                    ScriptsCommand.ShowScript.class
            })
    static class ScriptsCommand implements Callable<Integer> {

        @Override
        public Integer call() {
            CommandLine.usage(this, System.out);
            return 0;
        }

        @Command(name = "list", description = "List all scripts")
        static class ListScripts implements Callable<Integer> {
            @Option(names = {"-c", "--config"}, defaultValue = "config.yaml")
            private File configFile;

            @Override
            public Integer call() throws Exception {
                AppConfig config = AppConfig.load(configFile);
                Path scriptsDir = Paths.get(config.getReceiver().getBaseDir(), "scripts");
                ScriptLibrary library = new ScriptLibrary(scriptsDir);

                System.out.println();
                System.out.printf("%-20s %-25s %-10s %-10s%n", "NAME", "DISPLAY NAME", "CATEGORY", "BUILT-IN");
                System.out.println("─".repeat(70));

                for (ScriptLibrary.ScriptEntry script : library.getAllScripts()) {
                    System.out.printf("%-20s %-25s %-10s %-10s%n",
                            script.getName(),
                            script.getDisplayName(),
                            script.getCategory(),
                            script.isBuiltIn() ? "Yes" : "No");
                }

                return 0;
            }
        }

        @Command(name = "show", description = "Show script content")
        static class ShowScript implements Callable<Integer> {
            @Option(names = {"-c", "--config"}, defaultValue = "config.yaml")
            private File configFile;

            @Parameters(index = "0", description = "Script name")
            private String name;

            @Override
            public Integer call() throws Exception {
                AppConfig config = AppConfig.load(configFile);
                Path scriptsDir = Paths.get(config.getReceiver().getBaseDir(), "scripts");
                ScriptLibrary library = new ScriptLibrary(scriptsDir);

                ScriptLibrary.ScriptEntry entry = library.getScript(name);
                if (entry == null) {
                    System.err.println("Script not found: " + name);
                    return 1;
                }

                System.out.println();
                System.out.println("Script: " + entry.getDisplayName());
                System.out.println("─".repeat(50));
                System.out.println("Name:        " + entry.getName());
                System.out.println("Category:    " + entry.getCategory());
                System.out.println("Built-in:    " + entry.isBuiltIn());
                System.out.println("Description: " + entry.getDescription());
                System.out.println();
                System.out.println("Content:");
                System.out.println("─".repeat(50));
                System.out.println(library.getScriptContent(name));

                return 0;
            }
        }
    }

    // ========================================================================
    // QUERY COMMAND - Query/Retrieve from destinations
    // ========================================================================

    @Command(name = "query", description = "Query DICOM destinations")
    static class QueryCommand implements Callable<Integer> {

        @ParentCommand
        private DicomRouter parent;

        @Option(names = {"-d", "--destination"}, required = true, description = "Destination to query")
        private String destination;

        @Option(names = {"--patient-id"}, description = "Patient ID to search")
        private String patientId;

        @Option(names = {"--patient-name"}, description = "Patient name pattern")
        private String patientName;

        @Option(names = {"--study-date"}, description = "Study date (YYYYMMDD or range)")
        private String studyDate;

        @Option(names = {"--modality"}, description = "Modality (CT, MR, etc.)")
        private String modality;

        @Override
        public Integer call() throws Exception {
            AppConfig config = AppConfig.load(parent.configFile);
            AppConfig.Destination dest = config.getDestination(destination);

            if (dest == null) {
                System.err.println("Destination not found: " + destination);
                return 1;
            }

            if (dest instanceof AppConfig.DicomAeDestination) {
                return queryDicom((AppConfig.DicomAeDestination) dest);
            } else if (dest instanceof AppConfig.XnatDestination) {
                System.out.println("XNAT query via DICOMweb not yet implemented");
                return 1;
            } else {
                System.err.println("Cannot query this destination type: " + dest.getType());
                return 1;
            }
        }

        private Integer queryDicom(AppConfig.DicomAeDestination dest) throws Exception {
            DicomClient client = new DicomClient(destination, dest);

            // Build query keys
            Attributes queryKeys = new Attributes();
            queryKeys.setString(Tag.QueryRetrieveLevel, null, "STUDY");
            queryKeys.setNull(Tag.StudyInstanceUID, null);
            queryKeys.setNull(Tag.StudyDate, null);
            queryKeys.setNull(Tag.StudyTime, null);
            queryKeys.setNull(Tag.StudyDescription, null);
            queryKeys.setNull(Tag.NumberOfStudyRelatedSeries, null);
            queryKeys.setNull(Tag.NumberOfStudyRelatedInstances, null);

            if (patientId != null) queryKeys.setString(Tag.PatientID, null, patientId);
            if (patientName != null) queryKeys.setString(Tag.PatientName, null, patientName);
            if (studyDate != null) queryKeys.setString(Tag.StudyDate, null, studyDate);
            if (modality != null) queryKeys.setString(Tag.ModalitiesInStudy, null, modality);

            System.out.println("Querying " + destination + "...");

            List<Attributes> results = client.findStudies(queryKeys);

            System.out.println();
            System.out.printf("%-20s %-20s %-12s %-30s%n", "PATIENT ID", "STUDY DATE", "MODALITIES", "STUDY UID");
            System.out.println("─".repeat(90));

            for (Attributes result : results) {
                System.out.printf("%-20s %-20s %-12s %-30s%n",
                        result.getString(Tag.PatientID, ""),
                        result.getString(Tag.StudyDate, ""),
                        result.getString(Tag.ModalitiesInStudy, ""),
                        result.getString(Tag.StudyInstanceUID, ""));
            }

            System.out.println();
            System.out.println("Found " + results.size() + " studies");

            client.close();
            return 0;
        }
    }

    // ========================================================================
    // HISTORY COMMAND - View transfer history
    // ========================================================================

    @Command(name = "history", description = "View transfer history")
    static class HistoryCommand implements Callable<Integer> {

        @ParentCommand
        private DicomRouter parent;

        @Option(names = {"--ae-title"}, description = "Filter by AE Title")
        private String aeTitle;

        @Option(names = {"--date"}, description = "Date (YYYY-MM-DD, default: today)")
        private String date;

        @Option(names = {"--status"}, description = "Filter by status")
        private String status;

        @Override
        public Integer call() throws Exception {
            AppConfig config = AppConfig.load(parent.configFile);
            Path baseDir = Paths.get(config.getReceiver().getBaseDir());
            TransferTracker tracker = new TransferTracker(baseDir);

            LocalDate queryDate = date != null ? LocalDate.parse(date) : LocalDate.now();

            System.out.println();
            System.out.println("Transfer History for " + queryDate);
            System.out.println("─".repeat(80));

            if (aeTitle != null) {
                // Show history for specific AE Title
                List<TransferTracker.TransferRecord> transfers = tracker.getHistory(aeTitle, queryDate);
                printTransfers(transfers);
            } else {
                // Show global statistics
                TransferTracker.GlobalStatistics stats = tracker.getGlobalStatistics();
                System.out.println();
                System.out.println("Global Statistics:");
                System.out.println("  Total Transfers:      " + stats.getTotalTransfers());
                System.out.println("  Successful:           " + stats.getSuccessfulTransfers());
                System.out.println("  Failed:               " + stats.getFailedTransfers());
                System.out.println("  Currently Active:     " + stats.getActiveTransfers());
                System.out.printf("  Success Rate:         %.1f%%%n", stats.getSuccessRate());
            }

            return 0;
        }

        private void printTransfers(List<TransferTracker.TransferRecord> transfers) {
            System.out.printf("%-30s %-15s %-10s %-10s %-10s%n",
                    "TRANSFER ID", "STUDY UID", "FILES", "STATUS", "DURATION");
            System.out.println("─".repeat(80));

            for (TransferTracker.TransferRecord t : transfers) {
                String studyUid = t.getStudyUid();
                if (studyUid.length() > 13) {
                    studyUid = studyUid.substring(studyUid.length() - 13);
                }
                String duration = t.getTotalDurationMs() > 0 ?
                        String.format("%.1fs", t.getTotalDurationMs() / 1000.0) : "-";

                System.out.printf("%-30s %-15s %-10d %-10s %-10s%n",
                        t.getId(), studyUid, t.getFileCount(), t.getStatus(), duration);
            }

            System.out.println();
            System.out.println("Total: " + transfers.size() + " transfers");
        }
    }
}
