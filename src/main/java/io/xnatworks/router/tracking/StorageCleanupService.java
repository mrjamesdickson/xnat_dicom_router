/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.tracking;

import io.xnatworks.router.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.*;

/**
 * Scheduled service to clean up old study folders based on retention policy.
 * Runs daily and removes completed/failed study folders older than retention_days.
 * Also handles archive cleanup with separate retention period.
 */
public class StorageCleanupService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(StorageCleanupService.class);

    private final Path dataDir;
    private final int retentionDays;
    private final int archiveRetentionDays;
    private final int deletedRetentionDays;
    private final ScheduledExecutorService scheduler;

    public StorageCleanupService(AppConfig config) {
        this.dataDir = Paths.get(config.getDataDirectory());
        this.retentionDays = config.getResilience().getRetentionDays();
        this.archiveRetentionDays = config.getResilience().getArchiveRetentionDays();
        this.deletedRetentionDays = config.getResilience().getDeletedRetentionDays();

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "storage-cleanup");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start the cleanup scheduler.
     * Runs immediately on startup, then daily at midnight.
     */
    public void start() {
        log.info("Starting storage cleanup service (retention: {} days, archive: {} days, deleted: {} days)",
                retentionDays,
                archiveRetentionDays < 0 ? "disabled" : archiveRetentionDays,
                deletedRetentionDays < 0 ? "disabled" : deletedRetentionDays);

        // Run immediately, then every 24 hours
        scheduler.scheduleAtFixedRate(this::runCleanup, 0, 24, TimeUnit.HOURS);
    }

    /**
     * Run cleanup of old study folders.
     */
    private void runCleanup() {
        try {
            log.info("Running storage cleanup (retention: {} days, archive: {} days)...",
                    retentionDays, archiveRetentionDays);

            if (!Files.exists(dataDir)) {
                log.debug("Data directory does not exist: {}", dataDir);
                return;
            }

            Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
            Instant deletedCutoff = deletedRetentionDays >= 0
                    ? Instant.now().minus(deletedRetentionDays, ChronoUnit.DAYS)
                    : null; // null means disabled
            LocalDate archiveCutoffDate = archiveRetentionDays >= 0
                    ? LocalDate.now().minusDays(archiveRetentionDays)
                    : null; // null means disabled

            int completedDeleted = 0;
            int failedDeleted = 0;
            int softDeletedPermanentlyDeleted = 0;
            int archiveDeleted = 0;
            int historyDeleted = 0;
            int logsDeleted = 0;

            // Iterate through AE Title directories
            try (var aeDirs = Files.list(dataDir)) {
                for (Path aeDir : (Iterable<Path>) aeDirs.filter(Files::isDirectory)::iterator) {
                    String aeName = aeDir.getFileName().toString();
                    if (aeName.equals("scripts")) continue;

                    // Clean completed folders
                    completedDeleted += cleanOldStudyFolders(aeDir.resolve("completed"), cutoff);

                    // Clean failed folders (older than retention)
                    failedDeleted += cleanOldStudyFolders(aeDir.resolve("failed"), cutoff);

                    // Clean deleted folders (soft-deleted studies, uses separate retention period)
                    if (deletedCutoff != null) {
                        softDeletedPermanentlyDeleted += cleanOldStudyFolders(aeDir.resolve("deleted"), deletedCutoff);
                    }

                    // Clean archive folders (date-based cleanup for archives)
                    if (archiveCutoffDate != null) {
                        archiveDeleted += cleanOldArchiveFolders(aeDir.resolve("archive"), archiveCutoffDate);
                    }

                    // Clean old history JSON files
                    historyDeleted += cleanOldFiles(aeDir.resolve("history"), cutoff, "*.json");

                    // Clean old log CSV files
                    logsDeleted += cleanOldFiles(aeDir.resolve("logs"), cutoff, "*.csv");
                }
            }

            log.info("Storage cleanup completed: {} completed, {} failed, {} deleted, {} archived, {} history, {} logs removed",
                    completedDeleted, failedDeleted, softDeletedPermanentlyDeleted, archiveDeleted, historyDeleted, logsDeleted);

        } catch (Exception e) {
            log.error("Storage cleanup failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Clean archive folders based on date directory structure.
     * Archives are organized as archive/{date}/study_{uid}/
     */
    private int cleanOldArchiveFolders(Path archiveDir, LocalDate cutoffDate) {
        if (!Files.exists(archiveDir) || !Files.isDirectory(archiveDir)) {
            return 0;
        }

        int deleted = 0;

        try (var dateDirs = Files.list(archiveDir)) {
            for (Path dateDir : (Iterable<Path>) dateDirs.filter(Files::isDirectory)::iterator) {
                String dateName = dateDir.getFileName().toString();
                try {
                    LocalDate dirDate = LocalDate.parse(dateName, DateTimeFormatter.ISO_LOCAL_DATE);
                    if (dirDate.isBefore(cutoffDate)) {
                        // Delete all study folders in this date directory
                        try (var studyDirs = Files.list(dateDir)) {
                            for (Path studyDir : (Iterable<Path>) studyDirs.filter(Files::isDirectory)::iterator) {
                                try {
                                    log.debug("Deleting old archived study: {}", studyDir);
                                    deleteDirectory(studyDir);
                                    deleted++;
                                } catch (IOException e) {
                                    log.warn("Failed to delete archived study {}: {}", studyDir, e.getMessage());
                                }
                            }
                        }
                        // Delete the date directory if empty
                        try (var remaining = Files.list(dateDir)) {
                            if (remaining.findFirst().isEmpty()) {
                                Files.delete(dateDir);
                                log.debug("Deleted empty archive date directory: {}", dateDir);
                            }
                        }
                    }
                } catch (java.time.format.DateTimeParseException e) {
                    log.debug("Skipping non-date directory in archive: {}", dateName);
                } catch (IOException e) {
                    log.warn("Failed to process archive date directory {}: {}", dateDir, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to list archive directory {}: {}", archiveDir, e.getMessage());
        }

        return deleted;
    }

    /**
     * Clean study folders older than cutoff.
     */
    private int cleanOldStudyFolders(Path dir, Instant cutoff) {
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return 0;
        }

        int deleted = 0;

        try (var folders = Files.list(dir)) {
            for (Path folder : (Iterable<Path>) folders.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("study_"))::iterator) {

                try {
                    BasicFileAttributes attrs = Files.readAttributes(folder, BasicFileAttributes.class);
                    Instant modTime = attrs.lastModifiedTime().toInstant();

                    if (modTime.isBefore(cutoff)) {
                        log.debug("Deleting old study folder: {}", folder);
                        deleteDirectory(folder);
                        deleted++;
                    }
                } catch (IOException e) {
                    log.warn("Failed to check/delete folder {}: {}", folder, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to list directory {}: {}", dir, e.getMessage());
        }

        return deleted;
    }

    /**
     * Clean files matching pattern older than cutoff.
     */
    private int cleanOldFiles(Path dir, Instant cutoff, String pattern) {
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return 0;
        }

        int deleted = 0;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, pattern)) {
            for (Path file : stream) {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                    Instant modTime = attrs.lastModifiedTime().toInstant();

                    if (modTime.isBefore(cutoff)) {
                        log.debug("Deleting old file: {}", file);
                        Files.delete(file);
                        deleted++;
                    }
                } catch (IOException e) {
                    log.warn("Failed to check/delete file {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to list directory {}: {}", dir, e.getMessage());
        }

        return deleted;
    }

    /**
     * Recursively delete a directory and all its contents.
     */
    private void deleteDirectory(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Trigger an immediate cleanup (for testing or manual trigger via API).
     */
    public void triggerCleanup() {
        scheduler.execute(this::runCleanup);
    }

    @Override
    public void close() {
        log.info("Stopping storage cleanup service");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
