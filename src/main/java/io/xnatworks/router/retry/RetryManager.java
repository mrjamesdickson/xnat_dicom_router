/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.retry;

import io.xnatworks.router.archive.ArchiveManager;
import io.xnatworks.router.config.AppConfig;
import io.xnatworks.router.routing.DestinationManager;
import io.xnatworks.router.tracking.TransferTracker;
import io.xnatworks.router.xnat.XnatClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages retry operations for failed destinations.
 *
 * Features:
 * - Smart per-destination retry (only retry failed destinations, not entire study)
 * - Uses archived files (original or anonymized as appropriate)
 * - Respects max_retries and retry_delay_seconds from route config
 * - Tracks retry attempts and schedules next retry
 * - Provides API for manual retry triggers
 */
public class RetryManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RetryManager.class);

    private final AppConfig config;
    private final ArchiveManager archiveManager;
    private final DestinationManager destinationManager;
    private final TransferTracker transferTracker;
    private final Path baseDir;

    private final ScheduledExecutorService scheduler;
    private final ExecutorService retryExecutor;

    // Track pending retries: studyUid -> destination -> scheduled retry
    private final Map<String, Map<String, ScheduledFuture<?>>> pendingRetries = new ConcurrentHashMap<>();

    public RetryManager(AppConfig config, ArchiveManager archiveManager,
                        DestinationManager destinationManager, TransferTracker transferTracker,
                        Path baseDir) {
        this.config = config;
        this.archiveManager = archiveManager;
        this.destinationManager = destinationManager;
        this.transferTracker = transferTracker;
        this.baseDir = baseDir;

        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "retry-scheduler");
            t.setDaemon(true);
            return t;
        });

        this.retryExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "retry-worker");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start the retry manager and schedule scanning for failed destinations.
     */
    public void start() {
        log.info("Starting RetryManager");

        // Scan for failed destinations every 5 minutes
        scheduler.scheduleAtFixedRate(this::scanForFailedDestinations, 1, 5, TimeUnit.MINUTES);
    }

    /**
     * Scan all routes for studies with failed destinations that need retry.
     */
    private void scanForFailedDestinations() {
        log.debug("Scanning for failed destinations to retry");

        try {
            for (AppConfig.RouteConfig route : config.getRoutes()) {
                if (!route.isEnabled() || !route.isEnableArchive()) {
                    continue;
                }

                scanRouteForFailures(route);
            }
        } catch (Exception e) {
            log.error("Error scanning for failed destinations: {}", e.getMessage(), e);
        }
    }

    /**
     * Scan a specific route for studies with failed destinations.
     */
    private void scanRouteForFailures(AppConfig.RouteConfig route) {
        String aeTitle = route.getAeTitle();

        // Get archived studies with failed destinations
        List<ArchiveManager.ArchivedStudySummary> studies = archiveManager.listArchivedStudies(aeTitle, 100);

        for (ArchiveManager.ArchivedStudySummary summary : studies) {
            if (summary.getFailedDestinations() > 0) {
                // Get full archived study to check retry eligibility
                ArchiveManager.ArchivedStudy study = archiveManager.getArchivedStudy(aeTitle, summary.getStudyUid());
                if (study != null) {
                    checkAndScheduleRetries(route, study);
                }
            }
        }
    }

    /**
     * Check if a study's failed destinations are eligible for retry and schedule them.
     */
    private void checkAndScheduleRetries(AppConfig.RouteConfig route, ArchiveManager.ArchivedStudy study) {
        String studyUid = study.getStudyUid();
        String aeTitle = route.getAeTitle();

        Map<String, ArchiveManager.DestinationStatus> destStatuses = study.getDestinationStatuses();
        if (destStatuses == null) return;

        for (Map.Entry<String, ArchiveManager.DestinationStatus> entry : destStatuses.entrySet()) {
            String destName = entry.getKey();
            ArchiveManager.DestinationStatus status = entry.getValue();

            if (status.getStatus() != ArchiveManager.DestinationStatusEnum.FAILED &&
                status.getStatus() != ArchiveManager.DestinationStatusEnum.RETRY_PENDING) {
                continue;
            }

            // Find route destination config
            AppConfig.RouteDestination routeDest = route.getDestinations().stream()
                    .filter(d -> d.getDestination().equals(destName))
                    .findFirst()
                    .orElse(null);

            if (routeDest == null) {
                log.debug("[{}] Destination {} not found in route config for study {}",
                        aeTitle, destName, studyUid);
                continue;
            }

            // Check if retry is already scheduled
            if (isRetryScheduled(studyUid, destName)) {
                continue;
            }

            // Check retry eligibility
            int maxRetries = routeDest.getRetryCount();
            int currentAttempts = status.getAttempts();

            if (currentAttempts >= maxRetries) {
                log.debug("[{}] Study {} destination {} exceeded max retries ({}/{})",
                        aeTitle, studyUid, destName, currentAttempts, maxRetries);
                continue;
            }

            // Check if enough time has passed since last attempt
            LocalDateTime nextRetryAt = status.getNextRetryAt();
            if (nextRetryAt == null) {
                // Calculate next retry time based on delay
                int delaySeconds = routeDest.getRetryDelaySeconds();
                LocalDateTime lastAttempt = status.getLastAttemptAt();
                if (lastAttempt != null) {
                    nextRetryAt = lastAttempt.plusSeconds(delaySeconds);
                } else {
                    nextRetryAt = LocalDateTime.now();
                }
            }

            if (nextRetryAt.isAfter(LocalDateTime.now())) {
                // Schedule for later
                long delayMs = java.time.Duration.between(LocalDateTime.now(), nextRetryAt).toMillis();
                scheduleRetry(aeTitle, studyUid, destName, routeDest, Math.max(1000, delayMs));
            } else {
                // Ready to retry now
                scheduleRetry(aeTitle, studyUid, destName, routeDest, 0);
            }
        }
    }

    /**
     * Check if a retry is already scheduled for a study/destination.
     */
    private boolean isRetryScheduled(String studyUid, String destName) {
        Map<String, ScheduledFuture<?>> studyRetries = pendingRetries.get(studyUid);
        if (studyRetries == null) return false;

        ScheduledFuture<?> future = studyRetries.get(destName);
        return future != null && !future.isDone();
    }

    /**
     * Schedule a retry for a specific destination.
     */
    private void scheduleRetry(String aeTitle, String studyUid, String destName,
                                AppConfig.RouteDestination routeDest, long delayMs) {
        log.info("[{}] Scheduling retry for study {} -> {} in {}ms",
                aeTitle, studyUid, destName, delayMs);

        // Update status to RETRY_PENDING
        try {
            ArchiveManager.DestinationStatus status = archiveManager.loadDestinationStatus(aeTitle, studyUid, destName);
            if (status != null) {
                status.setStatus(ArchiveManager.DestinationStatusEnum.RETRY_PENDING);
                status.setNextRetryAt(LocalDateTime.now().plusNanos(delayMs * 1_000_000));
                archiveManager.saveDestinationStatus(aeTitle, studyUid, destName, status);
            }
        } catch (IOException e) {
            log.warn("[{}] Failed to update status for retry: {}", aeTitle, e.getMessage());
        }

        // Schedule the retry
        ScheduledFuture<?> future = scheduler.schedule(
                () -> executeRetry(aeTitle, studyUid, destName, routeDest),
                delayMs, TimeUnit.MILLISECONDS);

        // Track the scheduled retry
        pendingRetries.computeIfAbsent(studyUid, k -> new ConcurrentHashMap<>())
                      .put(destName, future);
    }

    /**
     * Execute a retry for a specific destination.
     */
    private void executeRetry(String aeTitle, String studyUid, String destName,
                               AppConfig.RouteDestination routeDest) {
        log.info("[{}] Executing retry for study {} -> {}", aeTitle, studyUid, destName);

        // Remove from pending
        Map<String, ScheduledFuture<?>> studyRetries = pendingRetries.get(studyUid);
        if (studyRetries != null) {
            studyRetries.remove(destName);
        }

        // Get archived study
        ArchiveManager.ArchivedStudy study = archiveManager.getArchivedStudy(aeTitle, studyUid);
        if (study == null) {
            log.error("[{}] Archived study not found for retry: {}", aeTitle, studyUid);
            return;
        }

        // Get current status
        ArchiveManager.DestinationStatus status = study.getDestinationStatuses().get(destName);
        if (status == null) {
            status = new ArchiveManager.DestinationStatus();
            status.setDestination(destName);
            status.setAttempts(0);
        }

        // Update status to PROCESSING
        status.setStatus(ArchiveManager.DestinationStatusEnum.PROCESSING);
        status.setAttempts(status.getAttempts() + 1);
        status.setLastAttemptAt(LocalDateTime.now());

        try {
            archiveManager.saveDestinationStatus(aeTitle, studyUid, destName, status);
        } catch (IOException e) {
            log.warn("[{}] Failed to update status: {}", aeTitle, e.getMessage());
        }

        // Execute the retry
        retryExecutor.submit(() -> {
            boolean success = false;
            String message = null;
            long startTime = System.currentTimeMillis();

            try {
                // Determine which files to use (anonymized if available, otherwise original)
                List<Path> filesToSend;
                if (routeDest.isAnonymize() && study.getAnonymizedFiles() != null && !study.getAnonymizedFiles().isEmpty()) {
                    filesToSend = study.getAnonymizedFiles();
                    log.debug("[{}] Using {} anonymized files for retry", aeTitle, filesToSend.size());
                } else if (study.getOriginalFiles() != null && !study.getOriginalFiles().isEmpty()) {
                    filesToSend = study.getOriginalFiles();
                    log.debug("[{}] Using {} original files for retry", aeTitle, filesToSend.size());
                } else {
                    throw new RuntimeException("No files available in archive for retry");
                }

                // Get destination config
                AppConfig.Destination dest = config.getDestination(destName);
                if (dest == null || !dest.isEnabled()) {
                    throw new RuntimeException("Destination not found or disabled: " + destName);
                }

                // Execute the forward based on destination type
                if (dest instanceof AppConfig.XnatDestination) {
                    success = retryToXnat(study, routeDest, destName, filesToSend);
                    message = success ? "Retry successful" : "Retry failed";
                } else if (dest instanceof AppConfig.DicomAeDestination) {
                    success = retryToDicom(destName, filesToSend);
                    message = success ? "Retry successful" : "Retry failed";
                } else if (dest instanceof AppConfig.FileDestination) {
                    success = retryToFile(destName, studyUid, filesToSend);
                    message = success ? "Retry successful" : "Retry failed";
                } else {
                    throw new RuntimeException("Unknown destination type: " + dest.getType());
                }

            } catch (Exception e) {
                log.error("[{}] Retry failed for {} -> {}: {}", aeTitle, studyUid, destName, e.getMessage(), e);
                message = e.getMessage();
            }

            long duration = System.currentTimeMillis() - startTime;

            // Update final status
            updateRetryStatus(aeTitle, studyUid, destName, success, message, duration,
                    routeDest.getRetryCount(), routeDest.getRetryDelaySeconds());
        });
    }

    /**
     * Retry sending to XNAT destination.
     */
    private boolean retryToXnat(ArchiveManager.ArchivedStudy study, AppConfig.RouteDestination routeDest,
                                 String destName, List<Path> files) throws Exception {
        XnatClient client = destinationManager.getXnatClient(destName);
        if (client == null) {
            throw new RuntimeException("No XNAT client available for: " + destName);
        }

        if (!destinationManager.isAvailable(destName)) {
            throw new RuntimeException("XNAT destination unavailable: " + destName);
        }

        // Create ZIP from archived files
        Path tempZip = Files.createTempFile("retry_upload_", ".zip");
        try {
            createZipFromFiles(files, tempZip);

            // Get project/subject/session info
            String projectId = routeDest.getProjectId();
            if (projectId == null || projectId.isEmpty()) {
                projectId = "RETRY";
            }

            String subjectPrefix = routeDest.getSubjectPrefix() != null ? routeDest.getSubjectPrefix() : "SUBJ";
            String sessionPrefix = routeDest.getSessionPrefix() != null ? routeDest.getSessionPrefix() : "SESSION";

            String subjectId = subjectPrefix + "_" + study.getStudyUid().substring(
                    Math.max(0, study.getStudyUid().length() - 8));
            String sessionLabel = sessionPrefix + "_" + java.time.LocalDate.now().toString().replace("-", "") +
                    "_" + study.getStudyUid().substring(Math.max(0, study.getStudyUid().length() - 8));

            log.info("[{}] Retry uploading to XNAT: project={}, subject={}, session={}",
                    study.getAeTitle(), projectId, subjectId, sessionLabel);

            XnatClient.UploadResult result = client.uploadWithRetry(
                    tempZip.toFile(), projectId, subjectId, sessionLabel,
                    routeDest.isAutoArchive(), 1, 0); // Single attempt for retry

            return result.isSuccess();

        } finally {
            Files.deleteIfExists(tempZip);
        }
    }

    /**
     * Retry sending to DICOM AE destination.
     */
    private boolean retryToDicom(String destName, List<Path> files) throws Exception {
        io.xnatworks.router.dicom.DicomClient client = destinationManager.getDicomClient(destName);
        if (client == null) {
            throw new RuntimeException("No DICOM client available for: " + destName);
        }

        if (!destinationManager.isAvailable(destName)) {
            throw new RuntimeException("DICOM destination unavailable: " + destName);
        }

        List<File> fileList = files.stream().map(Path::toFile).collect(Collectors.toList());
        io.xnatworks.router.dicom.DicomClient.StoreResult result = client.store(fileList);

        return result.isSuccess();
    }

    /**
     * Retry sending to file destination.
     */
    private boolean retryToFile(String destName, String studyUid, List<Path> files) throws Exception {
        List<File> fileList = files.stream().map(Path::toFile).collect(Collectors.toList());
        DestinationManager.ForwardResult result = destinationManager.forwardToFile(destName, fileList, studyUid);

        return result.isSuccess();
    }

    /**
     * Create a ZIP file from a list of paths.
     */
    private void createZipFromFiles(List<Path> files, Path zipPath) throws IOException {
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(zipPath))) {
            for (Path file : files) {
                java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(file.getFileName().toString());
                zos.putNextEntry(entry);
                Files.copy(file, zos);
                zos.closeEntry();
            }
        }
    }

    /**
     * Update destination status after retry attempt.
     */
    private void updateRetryStatus(String aeTitle, String studyUid, String destName,
                                    boolean success, String message, long durationMs,
                                    int maxRetries, int retryDelaySeconds) {
        try {
            ArchiveManager.DestinationStatus status = archiveManager.loadDestinationStatus(aeTitle, studyUid, destName);
            if (status == null) {
                status = new ArchiveManager.DestinationStatus();
                status.setDestination(destName);
            }

            status.setMessage(message);
            status.setDurationMs(durationMs);
            status.setLastAttemptAt(LocalDateTime.now());

            if (success) {
                status.setStatus(ArchiveManager.DestinationStatusEnum.SUCCESS);
                status.setNextRetryAt(null);
                log.info("[{}] Retry succeeded for {} -> {}", aeTitle, studyUid, destName);
            } else {
                if (status.getAttempts() >= maxRetries) {
                    status.setStatus(ArchiveManager.DestinationStatusEnum.FAILED);
                    status.setNextRetryAt(null);
                    status.setErrorDetails("Exceeded max retries (" + maxRetries + ")");
                    log.warn("[{}] Retry failed permanently for {} -> {} (exceeded {} retries)",
                            aeTitle, studyUid, destName, maxRetries);
                } else {
                    status.setStatus(ArchiveManager.DestinationStatusEnum.FAILED);
                    status.setNextRetryAt(LocalDateTime.now().plusSeconds(retryDelaySeconds));
                    log.info("[{}] Retry failed for {} -> {}, scheduling next retry in {}s",
                            aeTitle, studyUid, destName, retryDelaySeconds);
                }
            }

            archiveManager.saveDestinationStatus(aeTitle, studyUid, destName, status);

        } catch (IOException e) {
            log.error("[{}] Failed to update retry status: {}", aeTitle, e.getMessage(), e);
        }
    }

    // Public API methods

    /**
     * Manually trigger a retry for a specific destination.
     *
     * @param aeTitle  The route AE title
     * @param studyUid The study UID
     * @param destName The destination name to retry
     * @return true if retry was scheduled
     */
    public boolean retryDestination(String aeTitle, String studyUid, String destName) {
        log.info("[{}] Manual retry requested for {} -> {}", aeTitle, studyUid, destName);

        // Find route config
        AppConfig.RouteConfig route = config.findRouteByAeTitle(aeTitle);
        if (route == null) {
            log.error("Route not found: {}", aeTitle);
            return false;
        }

        // Find destination config
        AppConfig.RouteDestination routeDest = route.getDestinations().stream()
                .filter(d -> d.getDestination().equals(destName))
                .findFirst()
                .orElse(null);

        if (routeDest == null) {
            log.error("Destination {} not found in route {}", destName, aeTitle);
            return false;
        }

        // Cancel any existing scheduled retry
        cancelScheduledRetry(studyUid, destName);

        // Schedule immediate retry
        scheduleRetry(aeTitle, studyUid, destName, routeDest, 0);
        return true;
    }

    /**
     * Manually trigger retry for all failed destinations of a study.
     *
     * @param aeTitle  The route AE title
     * @param studyUid The study UID
     * @return Number of retries scheduled
     */
    public int retryAllFailedDestinations(String aeTitle, String studyUid) {
        log.info("[{}] Retry all failed destinations requested for {}", aeTitle, studyUid);

        ArchiveManager.ArchivedStudy study = archiveManager.getArchivedStudy(aeTitle, studyUid);
        if (study == null) {
            log.error("[{}] Study not found in archive: {}", aeTitle, studyUid);
            return 0;
        }

        int scheduled = 0;
        for (String destName : study.getFailedDestinationNames()) {
            if (retryDestination(aeTitle, studyUid, destName)) {
                scheduled++;
            }
        }

        return scheduled;
    }

    /**
     * Cancel a scheduled retry.
     */
    public void cancelScheduledRetry(String studyUid, String destName) {
        Map<String, ScheduledFuture<?>> studyRetries = pendingRetries.get(studyUid);
        if (studyRetries != null) {
            ScheduledFuture<?> future = studyRetries.remove(destName);
            if (future != null && !future.isDone()) {
                future.cancel(false);
                log.debug("Cancelled scheduled retry for {} -> {}", studyUid, destName);
            }
        }
    }

    /**
     * Get list of pending retries.
     */
    public List<PendingRetry> getPendingRetries() {
        List<PendingRetry> retries = new ArrayList<>();

        for (Map.Entry<String, Map<String, ScheduledFuture<?>>> studyEntry : pendingRetries.entrySet()) {
            String studyUid = studyEntry.getKey();
            for (Map.Entry<String, ScheduledFuture<?>> destEntry : studyEntry.getValue().entrySet()) {
                ScheduledFuture<?> future = destEntry.getValue();
                if (!future.isDone()) {
                    PendingRetry retry = new PendingRetry();
                    retry.setStudyUid(studyUid);
                    retry.setDestination(destEntry.getKey());
                    retry.setDelayMs(future.getDelay(TimeUnit.MILLISECONDS));
                    retries.add(retry);
                }
            }
        }

        return retries;
    }

    /**
     * Get studies with failed destinations for a route.
     */
    public List<FailedStudySummary> getFailedStudies(String aeTitle, int limit) {
        List<FailedStudySummary> failed = new ArrayList<>();

        List<ArchiveManager.ArchivedStudySummary> studies = archiveManager.listArchivedStudies(aeTitle, limit * 2);

        for (ArchiveManager.ArchivedStudySummary summary : studies) {
            if (summary.getFailedDestinations() > 0) {
                FailedStudySummary fs = new FailedStudySummary();
                fs.setStudyUid(summary.getStudyUid());
                fs.setAeTitle(summary.getAeTitle());
                fs.setArchivedAt(summary.getArchivedAt());
                fs.setTotalDestinations(summary.getDestinationCount());
                fs.setSuccessfulDestinations(summary.getSuccessfulDestinations());
                fs.setFailedDestinations(summary.getFailedDestinations());

                // Get failed destination names
                ArchiveManager.ArchivedStudy study = archiveManager.getArchivedStudy(aeTitle, summary.getStudyUid());
                if (study != null) {
                    fs.setFailedDestinationNames(study.getFailedDestinationNames());
                }

                failed.add(fs);

                if (failed.size() >= limit) break;
            }
        }

        return failed;
    }

    @Override
    public void close() {
        log.info("Stopping RetryManager");

        scheduler.shutdown();
        retryExecutor.shutdown();

        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            if (!retryExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                retryExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            retryExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // Data classes

    public static class PendingRetry {
        private String studyUid;
        private String destination;
        private long delayMs;

        public String getStudyUid() { return studyUid; }
        public void setStudyUid(String studyUid) { this.studyUid = studyUid; }

        public String getDestination() { return destination; }
        public void setDestination(String destination) { this.destination = destination; }

        public long getDelayMs() { return delayMs; }
        public void setDelayMs(long delayMs) { this.delayMs = delayMs; }
    }

    public static class FailedStudySummary {
        private String studyUid;
        private String aeTitle;
        private LocalDateTime archivedAt;
        private int totalDestinations;
        private int successfulDestinations;
        private int failedDestinations;
        private List<String> failedDestinationNames;

        public String getStudyUid() { return studyUid; }
        public void setStudyUid(String studyUid) { this.studyUid = studyUid; }

        public String getAeTitle() { return aeTitle; }
        public void setAeTitle(String aeTitle) { this.aeTitle = aeTitle; }

        public LocalDateTime getArchivedAt() { return archivedAt; }
        public void setArchivedAt(LocalDateTime archivedAt) { this.archivedAt = archivedAt; }

        public int getTotalDestinations() { return totalDestinations; }
        public void setTotalDestinations(int totalDestinations) { this.totalDestinations = totalDestinations; }

        public int getSuccessfulDestinations() { return successfulDestinations; }
        public void setSuccessfulDestinations(int successfulDestinations) { this.successfulDestinations = successfulDestinations; }

        public int getFailedDestinations() { return failedDestinations; }
        public void setFailedDestinations(int failedDestinations) { this.failedDestinations = failedDestinations; }

        public List<String> getFailedDestinationNames() { return failedDestinationNames; }
        public void setFailedDestinationNames(List<String> failedDestinationNames) { this.failedDestinationNames = failedDestinationNames; }
    }
}
