/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.tracking;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Tracks the status of DICOM transfers through the routing pipeline.
 * Provides real-time status and historical logging per AE Title.
 *
 * Transfer lifecycle:
 * RECEIVED -> PROCESSING -> FORWARDING -> COMPLETED/FAILED
 *
 * Each AE Title maintains its own:
 * - Active transfers (in-memory)
 * - Transfer history (JSON/CSV files in logs directory)
 * - Statistics
 */
public class TransferTracker {
    private static final Logger log = LoggerFactory.getLogger(TransferTracker.class);

    private final Path baseDir;
    private final ObjectMapper objectMapper;

    // Active transfers by ID
    private final Map<String, TransferRecord> activeTransfers = new ConcurrentHashMap<>();

    // Statistics per AE Title
    private final Map<String, AeStatistics> aeStatistics = new ConcurrentHashMap<>();

    // Global statistics
    private final AtomicLong totalTransfers = new AtomicLong(0);
    private final AtomicLong successfulTransfers = new AtomicLong(0);
    private final AtomicLong failedTransfers = new AtomicLong(0);

    public TransferTracker(Path baseDir) {
        this.baseDir = baseDir;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            log.error("Failed to create tracking directory: {}", e.getMessage(), e);
        }
    }

    /**
     * Create a new transfer record when a study is received.
     */
    public TransferRecord createTransfer(String aeTitle, String studyUid, String callingAe,
                                          long fileCount, long totalSize) {
        String transferId = generateTransferId(aeTitle, studyUid);

        TransferRecord record = new TransferRecord();
        record.setId(transferId);
        record.setAeTitle(aeTitle);
        record.setStudyUid(studyUid);
        record.setCallingAeTitle(callingAe);
        record.setFileCount(fileCount);
        record.setTotalSize(totalSize);
        record.setStatus(TransferStatus.RECEIVED);
        record.setReceivedAt(LocalDateTime.now());
        record.setDestinationResults(new ArrayList<>());

        activeTransfers.put(transferId, record);
        totalTransfers.incrementAndGet();

        // Update AE statistics
        getOrCreateAeStats(aeTitle).incrementReceived();

        // Log to AE-specific file
        logTransferEvent(record, "RECEIVED", "Study received from " + callingAe);

        log.info("[{}] Transfer created: {} ({} files, {} bytes)",
                aeTitle, transferId, fileCount, totalSize);

        return record;
    }

    /**
     * Update transfer status to processing (anonymization).
     */
    public void startProcessing(String transferId) {
        TransferRecord record = activeTransfers.get(transferId);
        if (record == null) {
            log.warn("Transfer not found: {}", transferId);
            return;
        }

        record.setStatus(TransferStatus.PROCESSING);
        record.setProcessingStartedAt(LocalDateTime.now());

        logTransferEvent(record, "PROCESSING", "Started processing/anonymization");
    }

    /**
     * Update transfer status to forwarding.
     */
    public void startForwarding(String transferId, List<String> destinations) {
        TransferRecord record = activeTransfers.get(transferId);
        if (record == null) {
            log.warn("Transfer not found: {}", transferId);
            return;
        }

        record.setStatus(TransferStatus.FORWARDING);
        record.setForwardingStartedAt(LocalDateTime.now());

        // Initialize destination results
        for (String dest : destinations) {
            DestinationResult destResult = new DestinationResult();
            destResult.setDestination(dest);
            destResult.setStatus(DestinationStatus.PENDING);
            record.getDestinationResults().add(destResult);
        }

        logTransferEvent(record, "FORWARDING", "Started forwarding to " + destinations.size() + " destination(s)");
    }

    /**
     * Update result for a specific destination.
     */
    public void updateDestinationResult(String transferId, String destination,
                                         DestinationStatus status, String message,
                                         long durationMs, int filesTransferred) {
        TransferRecord record = activeTransfers.get(transferId);
        if (record == null) {
            log.warn("Transfer not found: {}", transferId);
            return;
        }

        DestinationResult destResult = record.getDestinationResults().stream()
                .filter(d -> d.getDestination().equals(destination))
                .findFirst()
                .orElse(null);

        if (destResult == null) {
            destResult = new DestinationResult();
            destResult.setDestination(destination);
            record.getDestinationResults().add(destResult);
        }

        destResult.setStatus(status);
        destResult.setMessage(message);
        destResult.setDurationMs(durationMs);
        destResult.setFilesTransferred(filesTransferred);
        destResult.setCompletedAt(LocalDateTime.now());

        logTransferEvent(record, "DESTINATION_" + status.name(),
                destination + ": " + (message != null ? message : status.name()));

        // Check if all destinations are complete
        checkTransferCompletion(record);
    }

    /**
     * Check if all destinations are complete and update overall status.
     */
    private void checkTransferCompletion(TransferRecord record) {
        boolean allComplete = record.getDestinationResults().stream()
                .allMatch(d -> d.getStatus() == DestinationStatus.SUCCESS ||
                        d.getStatus() == DestinationStatus.FAILED);

        if (!allComplete) return;

        boolean anySuccess = record.getDestinationResults().stream()
                .anyMatch(d -> d.getStatus() == DestinationStatus.SUCCESS);

        boolean allSuccess = record.getDestinationResults().stream()
                .allMatch(d -> d.getStatus() == DestinationStatus.SUCCESS);

        record.setCompletedAt(LocalDateTime.now());

        if (allSuccess) {
            record.setStatus(TransferStatus.COMPLETED);
            successfulTransfers.incrementAndGet();
            getOrCreateAeStats(record.getAeTitle()).incrementSuccess();
            logTransferEvent(record, "COMPLETED", "All destinations successful");
        } else if (anySuccess) {
            record.setStatus(TransferStatus.PARTIAL);
            getOrCreateAeStats(record.getAeTitle()).incrementPartial();
            logTransferEvent(record, "PARTIAL", "Some destinations failed");
        } else {
            record.setStatus(TransferStatus.FAILED);
            failedTransfers.incrementAndGet();
            getOrCreateAeStats(record.getAeTitle()).incrementFailed();
            logTransferEvent(record, "FAILED", "All destinations failed");
        }

        // Save to history and remove from active
        saveToHistory(record);
        activeTransfers.remove(record.getId());

        log.info("[{}] Transfer completed: {} -> {}", record.getAeTitle(), record.getId(), record.getStatus());
    }

    /**
     * Mark transfer as failed.
     */
    public void failTransfer(String transferId, String reason) {
        TransferRecord record = activeTransfers.get(transferId);
        if (record == null) {
            log.warn("Transfer not found: {}", transferId);
            return;
        }

        record.setStatus(TransferStatus.FAILED);
        record.setCompletedAt(LocalDateTime.now());
        record.setErrorMessage(reason);

        failedTransfers.incrementAndGet();
        getOrCreateAeStats(record.getAeTitle()).incrementFailed();

        logTransferEvent(record, "FAILED", reason);
        saveToHistory(record);
        activeTransfers.remove(transferId);

        log.error("[{}] Transfer failed: {} - {}", record.getAeTitle(), transferId, reason);
    }

    /**
     * Get transfer by ID.
     */
    public TransferRecord getTransfer(String transferId) {
        return activeTransfers.get(transferId);
    }

    /**
     * Get all active transfers.
     */
    public List<TransferRecord> getActiveTransfers() {
        return new ArrayList<>(activeTransfers.values());
    }

    /**
     * Get active transfers for a specific AE Title.
     */
    public List<TransferRecord> getActiveTransfers(String aeTitle) {
        return activeTransfers.values().stream()
                .filter(t -> t.getAeTitle().equals(aeTitle))
                .collect(Collectors.toList());
    }

    /**
     * Get transfer history for an AE Title on a specific date.
     */
    public List<TransferRecord> getHistory(String aeTitle, LocalDate date) {
        Path historyFile = getHistoryFile(aeTitle, date);
        if (!Files.exists(historyFile)) {
            return Collections.emptyList();
        }

        try {
            TransferHistory history = objectMapper.readValue(historyFile.toFile(), TransferHistory.class);
            return history.getTransfers() != null ? history.getTransfers() : Collections.emptyList();
        } catch (IOException e) {
            log.error("Failed to read history file: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Get statistics for an AE Title.
     */
    public AeStatistics getStatistics(String aeTitle) {
        return aeStatistics.getOrDefault(aeTitle, new AeStatistics(aeTitle));
    }

    /**
     * Get all AE statistics.
     */
    public Map<String, AeStatistics> getAllStatistics() {
        return new HashMap<>(aeStatistics);
    }

    /**
     * Get global statistics.
     * Uses folder-based counts for completed/failed, which persist across restarts.
     */
    public GlobalStatistics getGlobalStatistics() {
        GlobalStatistics stats = new GlobalStatistics();

        // Count studies from storage folders (persists across restarts)
        StorageCounts storageCounts = countStudiesInStorage();

        // Total = completed + failed + incoming + processing
        long total = storageCounts.completed + storageCounts.failed +
                     storageCounts.incoming + storageCounts.processing;

        stats.setTotalTransfers(total);
        stats.setSuccessfulTransfers(storageCounts.completed);
        stats.setFailedTransfers(storageCounts.failed);
        stats.setActiveTransfers(storageCounts.incoming + storageCounts.processing);
        return stats;
    }

    /**
     * Count study folders in all AE Title storage directories.
     */
    private StorageCounts countStudiesInStorage() {
        StorageCounts counts = new StorageCounts();

        try {
            if (!Files.exists(baseDir)) {
                return counts;
            }

            // Iterate through AE Title directories
            try (var dirs = Files.list(baseDir)) {
                dirs.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().equals("scripts"))
                    .forEach(aeDir -> {
                        counts.incoming += countStudyFolders(aeDir.resolve("incoming"));
                        counts.processing += countStudyFolders(aeDir.resolve("processing"));
                        counts.completed += countStudyFolders(aeDir.resolve("completed"));
                        counts.failed += countStudyFolders(aeDir.resolve("failed"));
                    });
            }
        } catch (IOException e) {
            log.warn("Failed to count storage folders: {}", e.getMessage());
        }

        return counts;
    }

    /**
     * Count study_* folders in a directory.
     */
    private int countStudyFolders(Path dir) {
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return 0;
        }

        try (var files = Files.list(dir)) {
            return (int) files.filter(Files::isDirectory)
                              .filter(p -> p.getFileName().toString().startsWith("study_"))
                              .count();
        } catch (IOException e) {
            log.warn("Failed to count folders in {}: {}", dir, e.getMessage());
            return 0;
        }
    }

    /**
     * Simple class to hold storage counts.
     */
    private static class StorageCounts {
        int incoming = 0;
        int processing = 0;
        int completed = 0;
        int failed = 0;
    }

    /**
     * Get transfer history for an AE Title (recent transfers).
     */
    public List<TransferRecord> getTransferHistory(String aeTitle, int limit) {
        List<TransferRecord> history = new ArrayList<>();
        LocalDate date = LocalDate.now();

        // Load history from recent days until we have enough records
        for (int days = 0; days < 30 && history.size() < limit; days++) {
            history.addAll(getHistory(aeTitle, date.minusDays(days)));
        }

        // Sort by received time descending
        history.sort((a, b) -> {
            if (a.getReceivedAt() == null) return 1;
            if (b.getReceivedAt() == null) return -1;
            return b.getReceivedAt().compareTo(a.getReceivedAt());
        });

        return history.subList(0, Math.min(limit, history.size()));
    }

    /**
     * Get all transfer history across all AE Titles.
     */
    public List<TransferRecord> getAllTransferHistory(int limit) {
        List<TransferRecord> allHistory = new ArrayList<>();

        for (String aeTitle : aeStatistics.keySet()) {
            allHistory.addAll(getTransferHistory(aeTitle, limit));
        }

        // Sort by received time descending
        allHistory.sort((a, b) -> {
            if (a.getReceivedAt() == null) return 1;
            if (b.getReceivedAt() == null) return -1;
            return b.getReceivedAt().compareTo(a.getReceivedAt());
        });

        return allHistory.subList(0, Math.min(limit, allHistory.size()));
    }

    /**
     * Get detailed statistics for an AE Title.
     */
    public AeTitleStatistics getAeTitleStatistics(String aeTitle) {
        AeStatistics basic = aeStatistics.get(aeTitle);
        if (basic == null) {
            return null;
        }

        AeTitleStatistics stats = new AeTitleStatistics();
        stats.setAeTitle(aeTitle);
        stats.setTotalTransfers(basic.getReceived());
        stats.setSuccessfulTransfers(basic.getSuccess());
        stats.setFailedTransfers(basic.getFailed());
        stats.setActiveTransfers((int) getActiveTransfers(aeTitle).size());
        stats.setSuccessRate(basic.getSuccessRate());

        return stats;
    }

    /**
     * Get transfers by study UID.
     */
    public List<TransferRecord> getTransfersByStudyUid(String studyUid) {
        List<TransferRecord> matching = new ArrayList<>();

        // Check active transfers
        for (TransferRecord record : activeTransfers.values()) {
            if (studyUid.equals(record.getStudyUid())) {
                matching.add(record);
            }
        }

        // Check history
        for (String aeTitle : aeStatistics.keySet()) {
            for (TransferRecord record : getTransferHistory(aeTitle, 1000)) {
                if (studyUid.equals(record.getStudyUid())) {
                    matching.add(record);
                }
            }
        }

        return matching;
    }

    /**
     * Get failed transfers.
     */
    public List<TransferRecord> getFailedTransfers(String aeTitle, int limit) {
        List<TransferRecord> failed = new ArrayList<>();

        List<TransferRecord> history;
        if (aeTitle != null && !aeTitle.isEmpty()) {
            history = getTransferHistory(aeTitle, limit * 5);
        } else {
            history = getAllTransferHistory(limit * 5);
        }

        for (TransferRecord record : history) {
            if (record.getStatus() == TransferStatus.FAILED) {
                failed.add(record);
                if (failed.size() >= limit) break;
            }
        }

        return failed;
    }

    /**
     * Queue a failed transfer for retry.
     */
    public boolean queueForRetry(String transferId) {
        // This would need integration with the forward manager
        // For now, just log the intent
        log.info("Transfer {} queued for retry", transferId);
        return true;
    }

    // Private helper methods

    private String generateTransferId(String aeTitle, String studyUid) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String shortStudy = studyUid.length() > 8 ? studyUid.substring(studyUid.length() - 8) : studyUid;
        return aeTitle + "_" + timestamp + "_" + shortStudy;
    }

    private AeStatistics getOrCreateAeStats(String aeTitle) {
        return aeStatistics.computeIfAbsent(aeTitle, AeStatistics::new);
    }

    private void logTransferEvent(TransferRecord record, String event, String message) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String logLine = String.format("%s,%s,%s,%s,%s%n",
                timestamp, record.getId(), event, record.getStudyUid(), message);

        try {
            Path logsDir = baseDir.resolve(record.getAeTitle()).resolve("logs");
            Files.createDirectories(logsDir);

            Path logFile = logsDir.resolve("transfers_" + LocalDate.now().format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd")) + ".csv");

            // Write header if new file
            if (!Files.exists(logFile)) {
                Files.writeString(logFile, "timestamp,transfer_id,event,study_uid,message\n");
            }

            Files.writeString(logFile, logLine, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Failed to write transfer log: {}", e.getMessage());
        }
    }

    private void saveToHistory(TransferRecord record) {
        try {
            Path historyFile = getHistoryFile(record.getAeTitle(), LocalDate.now());
            Files.createDirectories(historyFile.getParent());

            TransferHistory history;
            if (Files.exists(historyFile)) {
                history = objectMapper.readValue(historyFile.toFile(), TransferHistory.class);
            } else {
                history = new TransferHistory();
                history.setDate(LocalDate.now());
                history.setAeTitle(record.getAeTitle());
                history.setTransfers(new ArrayList<>());
            }

            history.getTransfers().add(record);
            objectMapper.writeValue(historyFile.toFile(), history);

        } catch (IOException e) {
            log.error("Failed to save transfer history: {}", e.getMessage(), e);
        }
    }

    private Path getHistoryFile(String aeTitle, LocalDate date) {
        return baseDir.resolve(aeTitle).resolve("history")
                .resolve("transfers_" + date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + ".json");
    }

    // Data classes

    public enum TransferStatus {
        RECEIVED,    // Study received, waiting to process
        PROCESSING,  // Being anonymized/processed
        FORWARDING,  // Being forwarded to destinations
        COMPLETED,   // Successfully forwarded to all destinations
        PARTIAL,     // Some destinations succeeded, some failed
        FAILED       // Failed to forward
    }

    public enum DestinationStatus {
        PENDING,     // Not yet attempted
        IN_PROGRESS, // Currently forwarding
        SUCCESS,     // Successfully forwarded
        FAILED       // Failed to forward
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TransferRecord {
        private String id;
        private String aeTitle;
        private String studyUid;
        private String callingAeTitle;
        private long fileCount;
        private long totalSize;
        private TransferStatus status;
        private String errorMessage;
        private LocalDateTime receivedAt;
        private LocalDateTime processingStartedAt;
        private LocalDateTime forwardingStartedAt;
        private LocalDateTime completedAt;
        private List<DestinationResult> destinationResults;

        // Getters and setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getAeTitle() { return aeTitle; }
        public void setAeTitle(String aeTitle) { this.aeTitle = aeTitle; }

        public String getStudyUid() { return studyUid; }
        public void setStudyUid(String studyUid) { this.studyUid = studyUid; }

        public String getCallingAeTitle() { return callingAeTitle; }
        public void setCallingAeTitle(String callingAeTitle) { this.callingAeTitle = callingAeTitle; }

        public long getFileCount() { return fileCount; }
        public void setFileCount(long fileCount) { this.fileCount = fileCount; }

        public long getTotalSize() { return totalSize; }
        public void setTotalSize(long totalSize) { this.totalSize = totalSize; }

        public TransferStatus getStatus() { return status; }
        public void setStatus(TransferStatus status) { this.status = status; }

        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

        public LocalDateTime getReceivedAt() { return receivedAt; }
        public void setReceivedAt(LocalDateTime receivedAt) { this.receivedAt = receivedAt; }

        public LocalDateTime getProcessingStartedAt() { return processingStartedAt; }
        public void setProcessingStartedAt(LocalDateTime processingStartedAt) { this.processingStartedAt = processingStartedAt; }

        public LocalDateTime getForwardingStartedAt() { return forwardingStartedAt; }
        public void setForwardingStartedAt(LocalDateTime forwardingStartedAt) { this.forwardingStartedAt = forwardingStartedAt; }

        public LocalDateTime getCompletedAt() { return completedAt; }
        public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

        public List<DestinationResult> getDestinationResults() { return destinationResults; }
        public void setDestinationResults(List<DestinationResult> destinationResults) { this.destinationResults = destinationResults; }

        public long getTotalDurationMs() {
            if (receivedAt == null || completedAt == null) return 0;
            return java.time.Duration.between(receivedAt, completedAt).toMillis();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DestinationResult {
        private String destination;
        private DestinationStatus status;
        private String message;
        private long durationMs;
        private int filesTransferred;
        private LocalDateTime completedAt;

        public String getDestination() { return destination; }
        public void setDestination(String destination) { this.destination = destination; }

        public DestinationStatus getStatus() { return status; }
        public void setStatus(DestinationStatus status) { this.status = status; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public long getDurationMs() { return durationMs; }
        public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

        public int getFilesTransferred() { return filesTransferred; }
        public void setFilesTransferred(int filesTransferred) { this.filesTransferred = filesTransferred; }

        public LocalDateTime getCompletedAt() { return completedAt; }
        public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TransferHistory {
        private LocalDate date;
        private String aeTitle;
        private List<TransferRecord> transfers;

        public LocalDate getDate() { return date; }
        public void setDate(LocalDate date) { this.date = date; }

        public String getAeTitle() { return aeTitle; }
        public void setAeTitle(String aeTitle) { this.aeTitle = aeTitle; }

        public List<TransferRecord> getTransfers() { return transfers; }
        public void setTransfers(List<TransferRecord> transfers) { this.transfers = transfers; }
    }

    public static class AeStatistics {
        private final String aeTitle;
        private final AtomicLong received = new AtomicLong(0);
        private final AtomicLong success = new AtomicLong(0);
        private final AtomicLong partial = new AtomicLong(0);
        private final AtomicLong failed = new AtomicLong(0);

        public AeStatistics(String aeTitle) {
            this.aeTitle = aeTitle;
        }

        public void incrementReceived() { received.incrementAndGet(); }
        public void incrementSuccess() { success.incrementAndGet(); }
        public void incrementPartial() { partial.incrementAndGet(); }
        public void incrementFailed() { failed.incrementAndGet(); }

        public String getAeTitle() { return aeTitle; }
        public long getReceived() { return received.get(); }
        public long getSuccess() { return success.get(); }
        public long getPartial() { return partial.get(); }
        public long getFailed() { return failed.get(); }

        public double getSuccessRate() {
            long total = success.get() + partial.get() + failed.get();
            if (total == 0) return 0;
            return (success.get() * 100.0) / total;
        }
    }

    public static class GlobalStatistics {
        private long totalTransfers;
        private long successfulTransfers;
        private long failedTransfers;
        private int activeTransfers;
        private long totalBytes;
        private long averageTransferTime;

        public long getTotalTransfers() { return totalTransfers; }
        public void setTotalTransfers(long totalTransfers) { this.totalTransfers = totalTransfers; }

        public long getSuccessfulTransfers() { return successfulTransfers; }
        public void setSuccessfulTransfers(long successfulTransfers) { this.successfulTransfers = successfulTransfers; }

        public long getFailedTransfers() { return failedTransfers; }
        public void setFailedTransfers(long failedTransfers) { this.failedTransfers = failedTransfers; }

        public int getActiveTransfers() { return activeTransfers; }
        public void setActiveTransfers(int activeTransfers) { this.activeTransfers = activeTransfers; }

        public long getTotalBytes() { return totalBytes; }
        public void setTotalBytes(long totalBytes) { this.totalBytes = totalBytes; }

        public long getAverageTransferTime() { return averageTransferTime; }
        public void setAverageTransferTime(long averageTransferTime) { this.averageTransferTime = averageTransferTime; }

        public double getSuccessRate() {
            if (totalTransfers == 0) return 0;
            return (successfulTransfers * 100.0) / totalTransfers;
        }
    }

    /**
     * Extended statistics for a specific AE Title.
     */
    public static class AeTitleStatistics {
        private String aeTitle;
        private long totalTransfers;
        private long successfulTransfers;
        private long failedTransfers;
        private int activeTransfers;
        private double successRate;
        private long totalBytes;
        private long averageTransferTime;
        private Map<String, DestinationStatistics> destinationStatistics = new HashMap<>();

        public String getAeTitle() { return aeTitle; }
        public void setAeTitle(String aeTitle) { this.aeTitle = aeTitle; }

        public long getTotalTransfers() { return totalTransfers; }
        public void setTotalTransfers(long totalTransfers) { this.totalTransfers = totalTransfers; }

        public long getSuccessfulTransfers() { return successfulTransfers; }
        public void setSuccessfulTransfers(long successfulTransfers) { this.successfulTransfers = successfulTransfers; }

        public long getFailedTransfers() { return failedTransfers; }
        public void setFailedTransfers(long failedTransfers) { this.failedTransfers = failedTransfers; }

        public int getActiveTransfers() { return activeTransfers; }
        public void setActiveTransfers(int activeTransfers) { this.activeTransfers = activeTransfers; }

        public double getSuccessRate() { return successRate; }
        public void setSuccessRate(double successRate) { this.successRate = successRate; }

        public long getTotalBytes() { return totalBytes; }
        public void setTotalBytes(long totalBytes) { this.totalBytes = totalBytes; }

        public long getAverageTransferTime() { return averageTransferTime; }
        public void setAverageTransferTime(long averageTransferTime) { this.averageTransferTime = averageTransferTime; }

        public Map<String, DestinationStatistics> getDestinationStatistics() { return destinationStatistics; }
        public void setDestinationStatistics(Map<String, DestinationStatistics> destinationStatistics) {
            this.destinationStatistics = destinationStatistics;
        }
    }

    /**
     * Statistics for a specific destination.
     */
    public static class DestinationStatistics {
        private long successful;
        private long failed;

        public long getSuccessful() { return successful; }
        public void setSuccessful(long successful) { this.successful = successful; }

        public long getFailed() { return failed; }
        public void setFailed(long failed) { this.failed = failed; }

        public double getSuccessRate() {
            long total = successful + failed;
            if (total == 0) return 0;
            return (successful * 100.0) / total;
        }
    }
}
