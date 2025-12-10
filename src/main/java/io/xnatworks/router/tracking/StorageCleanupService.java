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
import java.time.temporal.ChronoUnit;
import java.util.concurrent.*;

/**
 * Scheduled service to clean up old study folders based on retention policy.
 * Runs daily and removes completed/failed study folders older than retention_days.
 */
public class StorageCleanupService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(StorageCleanupService.class);

    private final Path dataDir;
    private final int retentionDays;
    private final ScheduledExecutorService scheduler;

    public StorageCleanupService(AppConfig config) {
        this.dataDir = Paths.get(config.getDataDirectory());
        this.retentionDays = config.getResilience().getRetentionDays();

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
        log.info("Starting storage cleanup service (retention: {} days)", retentionDays);

        // Run immediately, then every 24 hours
        scheduler.scheduleAtFixedRate(this::runCleanup, 0, 24, TimeUnit.HOURS);
    }

    /**
     * Run cleanup of old study folders.
     */
    private void runCleanup() {
        try {
            log.info("Running storage cleanup (removing folders older than {} days)...", retentionDays);

            if (!Files.exists(dataDir)) {
                log.debug("Data directory does not exist: {}", dataDir);
                return;
            }

            Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
            int completedDeleted = 0;
            int failedDeleted = 0;
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

                    // Clean old history JSON files
                    historyDeleted += cleanOldFiles(aeDir.resolve("history"), cutoff, "*.json");

                    // Clean old log CSV files
                    logsDeleted += cleanOldFiles(aeDir.resolve("logs"), cutoff, "*.csv");
                }
            }

            log.info("Storage cleanup completed: {} completed, {} failed, {} history, {} logs deleted",
                    completedDeleted, failedDeleted, historyDeleted, logsDeleted);

        } catch (Exception e) {
            log.error("Storage cleanup failed: {}", e.getMessage(), e);
        }
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
