/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.dicom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Monitors a folder for DICOM studies and triggers completion callbacks after a quiet period.
 * Uses Java's WatchService to efficiently detect file system changes.
 *
 * Directory structure expected:
 * {watchDir}/
 *   └── {StudyInstanceUID}/
 *       └── {SeriesInstanceUID}/
 *           └── {SOPInstanceUID}.dcm
 */
public class FolderWatcher implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(FolderWatcher.class);

    private final Path watchDir;
    private final String aeTitle;
    private final long quietTimeMs;
    private final Consumer<DicomReceiver.ReceivedStudy> onStudyComplete;

    private WatchService watchService;
    private ScheduledExecutorService scheduler;
    private ExecutorService watchExecutor;
    private volatile boolean running = false;

    // Track last activity time for each study
    private final ConcurrentHashMap<String, Long> studyLastActivity = new ConcurrentHashMap<>();
    // Track which studies have already been completed
    private final Set<String> completedStudies = ConcurrentHashMap.newKeySet();

    /**
     * Create a folder watcher for DICOM studies.
     *
     * @param watchDir the directory to watch (e.g., incoming/)
     * @param aeTitle the AE title for logging and study metadata
     * @param quietTimeSeconds seconds of no activity before study is considered complete
     * @param onStudyComplete callback when study is ready
     */
    public FolderWatcher(Path watchDir, String aeTitle, int quietTimeSeconds, Consumer<DicomReceiver.ReceivedStudy> onStudyComplete) {
        this.watchDir = watchDir;
        this.aeTitle = aeTitle;
        this.quietTimeMs = quietTimeSeconds * 1000L;
        this.onStudyComplete = onStudyComplete;
    }

    /**
     * Start watching the folder.
     */
    public void start() throws IOException {
        if (running) {
            log.warn("[{}] FolderWatcher already running", aeTitle);
            return;
        }

        Files.createDirectories(watchDir);

        watchService = FileSystems.getDefault().newWatchService();

        // Register watch directory and all existing subdirectories
        registerDirectory(watchDir);

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "folder-watcher-scheduler-" + aeTitle);
            t.setDaemon(true);
            return t;
        });

        watchExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "folder-watcher-" + aeTitle);
            t.setDaemon(true);
            return t;
        });

        running = true;

        // Start watching for file changes
        watchExecutor.submit(this::watchLoop);

        // Start periodic check for completed studies
        scheduler.scheduleAtFixedRate(this::checkCompletedStudies,
            5, 5, TimeUnit.SECONDS);

        // Initial scan for existing studies
        scanExistingStudies();

        log.info("[{}] FolderWatcher started monitoring: {} (quiet time: {}s)",
            aeTitle, watchDir, quietTimeMs / 1000);
    }

    /**
     * Register a directory and its subdirectories with the watch service.
     */
    private void registerDirectory(Path dir) throws IOException {
        // Register for create, modify, delete events
        dir.register(watchService,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE);

        // Also register all subdirectories (study and series folders)
        try (Stream<Path> paths = Files.walk(dir, 2)) {
            paths.filter(Files::isDirectory)
                 .filter(p -> !p.equals(dir))
                 .forEach(p -> {
                     try {
                         p.register(watchService,
                             StandardWatchEventKinds.ENTRY_CREATE,
                             StandardWatchEventKinds.ENTRY_MODIFY,
                             StandardWatchEventKinds.ENTRY_DELETE);
                     } catch (IOException e) {
                         log.warn("[{}] Failed to register watch for {}: {}", aeTitle, p, e.getMessage());
                     }
                 });
        }
    }

    /**
     * Main watch loop - processes WatchService events.
     */
    private void watchLoop() {
        log.debug("[{}] Watch loop started", aeTitle);

        while (running) {
            try {
                // Wait for events (with timeout to allow for shutdown)
                WatchKey key = watchService.poll(1, TimeUnit.SECONDS);
                if (key == null) {
                    continue;
                }

                Path dir = (Path) key.watchable();

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();

                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        log.warn("[{}] Watch event overflow - some events may have been lost", aeTitle);
                        continue;
                    }

                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                    Path fileName = pathEvent.context();
                    Path fullPath = dir.resolve(fileName);

                    handleFileEvent(kind, fullPath, dir);
                }

                // Reset key to receive further events
                boolean valid = key.reset();
                if (!valid) {
                    log.debug("[{}] Watch key no longer valid for {}", aeTitle, dir);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                log.debug("[{}] Watch service closed", aeTitle);
                break;
            } catch (Exception e) {
                log.error("[{}] Error in watch loop: {}", aeTitle, e.getMessage(), e);
            }
        }

        log.debug("[{}] Watch loop ended", aeTitle);
    }

    /**
     * Handle a file system event.
     */
    private void handleFileEvent(WatchEvent.Kind<?> kind, Path fullPath, Path parentDir) {
        try {
            // Determine which study this event belongs to
            String studyUid = getStudyUidFromPath(fullPath, parentDir);
            if (studyUid == null) {
                return;
            }

            if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                // New file or directory created
                if (Files.isDirectory(fullPath)) {
                    // Register new directory for watching
                    try {
                        fullPath.register(watchService,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.ENTRY_DELETE);
                        log.debug("[{}] Registered watch for new directory: {}", aeTitle, fullPath);
                    } catch (IOException e) {
                        log.warn("[{}] Failed to register watch for {}: {}", aeTitle, fullPath, e.getMessage());
                    }
                }

                // Update study activity
                updateStudyActivity(studyUid);
                log.trace("[{}] CREATE: {} in study {}", aeTitle, fullPath.getFileName(), studyUid);

            } else if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                // File modified
                updateStudyActivity(studyUid);
                log.trace("[{}] MODIFY: {} in study {}", aeTitle, fullPath.getFileName(), studyUid);

            } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                // File or directory deleted - still counts as activity
                log.trace("[{}] DELETE: {} in study {}", aeTitle, fullPath.getFileName(), studyUid);
            }

        } catch (Exception e) {
            log.error("[{}] Error handling file event: {}", aeTitle, e.getMessage(), e);
        }
    }

    /**
     * Extract study UID from a file path.
     * Expected structure: watchDir/{studyUid}/{seriesUid}/{file}.dcm
     */
    private String getStudyUidFromPath(Path fullPath, Path parentDir) {
        // Calculate relative path from watch directory
        Path relativePath = watchDir.relativize(fullPath);
        int nameCount = relativePath.getNameCount();

        if (nameCount == 0) {
            return null;
        }

        // First component is always the study UID
        return relativePath.getName(0).toString();
    }

    /**
     * Update the last activity time for a study.
     */
    private void updateStudyActivity(String studyUid) {
        if (completedStudies.contains(studyUid)) {
            // Study already completed, ignore further activity
            // (This can happen if the study is being moved/processed)
            return;
        }

        long now = System.currentTimeMillis();
        Long previous = studyLastActivity.put(studyUid, now);

        if (previous == null) {
            log.debug("[{}] New study detected: {}", aeTitle, studyUid);
        }
    }

    /**
     * Scan for existing studies in the watch directory.
     */
    private void scanExistingStudies() {
        try (Stream<Path> studyDirs = Files.list(watchDir)) {
            List<Path> studies = studyDirs
                .filter(Files::isDirectory)
                .collect(Collectors.toList());

            if (studies.isEmpty()) {
                log.debug("[{}] No existing studies found", aeTitle);
                return;
            }

            log.info("[{}] Found {} existing study directories", aeTitle, studies.size());

            for (Path studyDir : studies) {
                String studyUid = studyDir.getFileName().toString();

                if (completedStudies.contains(studyUid)) {
                    continue;
                }

                // Get the most recent modification time of any file in the study
                long lastModified = getStudyLastModified(studyDir);
                if (lastModified > 0) {
                    studyLastActivity.put(studyUid, lastModified);
                    log.debug("[{}] Existing study: {} last modified at {}",
                        aeTitle, studyUid, lastModified);
                }
            }

        } catch (IOException e) {
            log.error("[{}] Error scanning existing studies: {}", aeTitle, e.getMessage(), e);
        }
    }

    /**
     * Get the most recent modification time of any file in a study directory.
     */
    private long getStudyLastModified(Path studyDir) {
        try (Stream<Path> files = Files.walk(studyDir)) {
            return files
                .filter(Files::isRegularFile)
                .mapToLong(p -> {
                    try {
                        return Files.getLastModifiedTime(p).toMillis();
                    } catch (IOException e) {
                        return 0;
                    }
                })
                .max()
                .orElse(0);
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Periodically check for studies that have been quiet long enough to complete.
     */
    private void checkCompletedStudies() {
        if (!running) {
            return;
        }

        long now = System.currentTimeMillis();
        long cutoffTime = now - quietTimeMs;

        for (Map.Entry<String, Long> entry : studyLastActivity.entrySet()) {
            String studyUid = entry.getKey();
            long lastActivity = entry.getValue();

            if (lastActivity <= cutoffTime && !completedStudies.contains(studyUid)) {
                // Study has been quiet long enough
                completeStudy(studyUid);
            }
        }
    }

    /**
     * Mark a study as complete and trigger the callback.
     */
    private void completeStudy(String studyUid) {
        // Mark as completed first to prevent duplicate processing
        if (!completedStudies.add(studyUid)) {
            // Already completed by another thread
            return;
        }

        studyLastActivity.remove(studyUid);

        Path studyPath = watchDir.resolve(studyUid);
        if (!Files.exists(studyPath)) {
            log.warn("[{}] Study directory no longer exists: {}", aeTitle, studyUid);
            return;
        }

        try {
            // Count files and total size
            long fileCount;
            long totalSize;
            try (Stream<Path> files = Files.walk(studyPath)) {
                List<Path> fileList = files.filter(Files::isRegularFile).collect(Collectors.toList());
                fileCount = fileList.size();
                totalSize = fileList.stream()
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .sum();
            }

            if (fileCount == 0) {
                log.debug("[{}] Study {} has no files, skipping", aeTitle, studyUid);
                completedStudies.remove(studyUid); // Allow retry if files appear later
                return;
            }

            log.info("[{}] Study complete (quiet period elapsed): {} ({} files, {} bytes)",
                aeTitle, studyUid, fileCount, totalSize);

            // Create ReceivedStudy and trigger callback
            DicomReceiver.ReceivedStudy study = new DicomReceiver.ReceivedStudy();
            study.setStudyUid(studyUid);
            study.setPath(studyPath);
            study.setFileCount(fileCount);
            study.setTotalSize(totalSize);
            study.setAeTitle(aeTitle);
            study.setCallingAeTitle("FOLDER_WATCHER");
            study.setReceivedAt(LocalDateTime.now());

            if (onStudyComplete != null) {
                onStudyComplete.accept(study);
            }

        } catch (IOException e) {
            log.error("[{}] Error completing study {}: {}", aeTitle, studyUid, e.getMessage(), e);
            completedStudies.remove(studyUid); // Allow retry
        }
    }

    /**
     * Manually mark a study as no longer in the incoming folder
     * (e.g., after it's been moved to processing/completed).
     */
    public void markStudyProcessed(String studyUid) {
        completedStudies.add(studyUid);
        studyLastActivity.remove(studyUid);
    }

    /**
     * Reset a study so it can be processed again.
     */
    public void resetStudy(String studyUid) {
        completedStudies.remove(studyUid);
        studyLastActivity.remove(studyUid);
    }

    /**
     * Stop watching the folder.
     */
    public void stop() {
        log.info("[{}] Stopping FolderWatcher", aeTitle);
        running = false;

        if (scheduler != null) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (watchExecutor != null) {
            watchExecutor.shutdown();
            try {
                watchExecutor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                log.warn("[{}] Error closing watch service: {}", aeTitle, e.getMessage());
            }
        }

        log.info("[{}] FolderWatcher stopped", aeTitle);
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * Check if the watcher is running.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Get the number of studies currently being tracked.
     */
    public int getActiveStudyCount() {
        return studyLastActivity.size();
    }

    /**
     * Get the list of studies currently being tracked.
     */
    public Set<String> getActiveStudies() {
        return new HashSet<>(studyLastActivity.keySet());
    }

    /**
     * Get the watch directory.
     */
    public Path getWatchDir() {
        return watchDir;
    }
}
