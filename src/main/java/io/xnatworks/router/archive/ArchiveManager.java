/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.archive;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.xnatworks.router.anon.AnonymizationAuditService;
import io.xnatworks.router.anon.ScriptLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages the archive of original and anonymized DICOM files.
 *
 * Provides:
 * - Preservation of original files for audit and retry purposes
 * - Storage of anonymized files alongside originals
 * - Generation of audit reports comparing original vs anonymized
 * - Per-destination status tracking within the archive
 *
 * Archive structure:
 * {baseDir}/{aeTitle}/archive/{date}/study_{uid}/
 *   ├── original/           - Original DICOM files
 *   ├── anonymized/         - Anonymized DICOM files (if applicable)
 *   ├── audit_report.json   - Anonymization diff report
 *   └── destinations/       - Per-destination tracking
 *       └── {dest_name}.json
 */
public class ArchiveManager {
    private static final Logger log = LoggerFactory.getLogger(ArchiveManager.class);

    private static final String ARCHIVE_DIR = "archive";
    private static final String ORIGINAL_DIR = "original";
    private static final String ANONYMIZED_DIR = "anonymized";
    private static final String DESTINATIONS_DIR = "destinations";
    private static final String AUDIT_REPORT_FILE = "audit_report.json";
    private static final String ARCHIVE_METADATA_FILE = "archive_metadata.json";

    private final Path baseDir;
    private final ObjectMapper objectMapper;
    private final ScriptLibrary scriptLibrary;
    private final AnonymizationAuditService auditService;

    public ArchiveManager(Path baseDir, ScriptLibrary scriptLibrary) {
        this.baseDir = baseDir;
        this.scriptLibrary = scriptLibrary;
        this.auditService = new AnonymizationAuditService(scriptLibrary);

        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Archive original DICOM files for a study.
     * Copies files to archive/{date}/study_{uid}/original/
     *
     * @param aeTitle       The AE Title (route) for this study
     * @param studyUid      The Study Instance UID
     * @param sourceDir     Directory containing the original DICOM files
     * @param callingAeTitle The calling AE title that sent the study
     * @return Path to the archived original files directory
     */
    public Path archiveOriginal(String aeTitle, String studyUid, Path sourceDir, String callingAeTitle) throws IOException {
        log.info("[{}] Archiving original files for study {}", aeTitle, studyUid);

        Path archiveStudyDir = getArchiveStudyDir(aeTitle, studyUid);
        Path originalDir = archiveStudyDir.resolve(ORIGINAL_DIR);
        Files.createDirectories(originalDir);

        // Copy all files from source to archive
        int filesCopied = copyDirectory(sourceDir, originalDir);
        log.debug("[{}] Copied {} files to archive/original", aeTitle, filesCopied);

        // Create archive metadata
        ArchiveMetadata metadata = new ArchiveMetadata();
        metadata.setStudyUid(studyUid);
        metadata.setAeTitle(aeTitle);
        metadata.setCallingAeTitle(callingAeTitle);
        metadata.setArchivedAt(LocalDateTime.now());
        metadata.setOriginalFileCount(filesCopied);
        metadata.setOriginalPath(sourceDir.toString());

        saveMetadata(archiveStudyDir, metadata);

        return originalDir;
    }

    /**
     * Archive anonymized DICOM files for a study.
     * Copies files to archive/{date}/study_{uid}/anonymized/
     *
     * @param aeTitle       The AE Title (route) for this study
     * @param studyUid      The Study Instance UID
     * @param anonymizedDir Directory containing the anonymized DICOM files
     * @param scriptName    Name of the anonymization script used
     * @return Path to the archived anonymized files directory
     */
    public Path archiveAnonymized(String aeTitle, String studyUid, Path anonymizedDir, String scriptName) throws IOException {
        log.info("[{}] Archiving anonymized files for study {} (script: {})", aeTitle, studyUid, scriptName);

        Path archiveStudyDir = getArchiveStudyDir(aeTitle, studyUid);
        Path archiveAnonDir = archiveStudyDir.resolve(ANONYMIZED_DIR);
        Files.createDirectories(archiveAnonDir);

        // Copy all files from anonymized directory to archive
        int filesCopied = copyDirectory(anonymizedDir, archiveAnonDir);
        log.debug("[{}] Copied {} anonymized files to archive", aeTitle, filesCopied);

        // Update metadata
        ArchiveMetadata metadata = loadMetadata(archiveStudyDir);
        if (metadata == null) {
            metadata = new ArchiveMetadata();
            metadata.setStudyUid(studyUid);
            metadata.setAeTitle(aeTitle);
            metadata.setArchivedAt(LocalDateTime.now());
        }
        metadata.setAnonymizedAt(LocalDateTime.now());
        metadata.setAnonymizedFileCount(filesCopied);
        metadata.setAnonymizationScript(scriptName);

        saveMetadata(archiveStudyDir, metadata);

        return archiveAnonDir;
    }

    /**
     * Archive anonymized DICOM files from a directory with broker info.
     * Delegates to archiveAnonymized and adds broker info to metadata.
     *
     * @param aeTitle         The AE Title (route) for this study
     * @param studyUid        The Study Instance UID
     * @param anonymizedDir   Directory containing anonymized files
     * @param scriptName      Name of the anonymization script used
     * @param brokerName      Name of the honest broker used (may be null)
     * @param hashUidsEnabled Whether UID hashing was enabled
     * @return Path to the archived anonymized files directory
     */
    public Path archiveAnonymized(String aeTitle, String studyUid, Path anonymizedDir,
                                  String scriptName, String brokerName, boolean hashUidsEnabled) throws IOException {
        Path result = archiveAnonymized(aeTitle, studyUid, anonymizedDir, scriptName);
        updateBrokerInfo(aeTitle, studyUid, brokerName, hashUidsEnabled);
        return result;
    }

    /**
     * Archive anonymized DICOM files from a ZIP file.
     * Extracts files from the ZIP to archive/{date}/study_{uid}/anonymized/
     *
     * This method supports the streaming anonymization workflow where anonymized files
     * are written directly to a ZIP without intermediate disk storage.
     *
     * @param aeTitle    The AE Title (route) for this study
     * @param studyUid   The Study Instance UID
     * @param zipFile    ZIP file containing anonymized DICOM files
     * @param scriptName Name of the anonymization script used
     * @return Path to the archived anonymized files directory
     */
    public Path archiveAnonymizedFromZip(String aeTitle, String studyUid, Path zipFile, String scriptName) throws IOException {
        log.info("[{}] Archiving anonymized files from ZIP for study {} (script: {})", aeTitle, studyUid, scriptName);

        Path archiveStudyDir = getArchiveStudyDir(aeTitle, studyUid);
        Path archiveAnonDir = archiveStudyDir.resolve(ANONYMIZED_DIR);
        Files.createDirectories(archiveAnonDir);

        int filesExtracted = 0;

        // Use ZipFile instead of ZipInputStream for more reliable extraction
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(zipFile.toFile())) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            byte[] buffer = new byte[8192];

            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }

                String fileName = entry.getName();
                // Handle nested paths in ZIP - extract just the filename
                if (fileName.contains("/")) {
                    fileName = fileName.substring(fileName.lastIndexOf('/') + 1);
                }
                if (fileName.contains("\\")) {
                    fileName = fileName.substring(fileName.lastIndexOf('\\') + 1);
                }

                Path destFile = archiveAnonDir.resolve(fileName);

                try (java.io.InputStream is = zip.getInputStream(entry);
                     java.io.OutputStream fos = Files.newOutputStream(destFile)) {
                    int len;
                    while ((len = is.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                }
                filesExtracted++;
                log.trace("[{}] Extracted file {} from ZIP", aeTitle, fileName);
            }
        }

        log.debug("[{}] Extracted {} anonymized files from ZIP to archive", aeTitle, filesExtracted);

        // Update metadata
        ArchiveMetadata metadata = loadMetadata(archiveStudyDir);
        if (metadata == null) {
            metadata = new ArchiveMetadata();
            metadata.setStudyUid(studyUid);
            metadata.setAeTitle(aeTitle);
            metadata.setArchivedAt(LocalDateTime.now());
        }
        metadata.setAnonymizedAt(LocalDateTime.now());
        metadata.setAnonymizedFileCount(filesExtracted);
        metadata.setAnonymizationScript(scriptName);

        saveMetadata(archiveStudyDir, metadata);

        return archiveAnonDir;
    }

    /**
     * Update broker information in archive metadata.
     *
     * @param aeTitle         The AE Title (route) for this study
     * @param studyUid        The Study Instance UID
     * @param brokerName      Name of the honest broker used (may be null)
     * @param hashUidsEnabled Whether UID hashing was enabled
     */
    public void updateBrokerInfo(String aeTitle, String studyUid, String brokerName, boolean hashUidsEnabled) throws IOException {
        Path archiveStudyDir = findArchivedStudyDir(aeTitle, studyUid);
        if (archiveStudyDir == null) {
            log.warn("[{}] Cannot update broker info - archived study not found: {}", aeTitle, studyUid);
            return;
        }

        ArchiveMetadata metadata = loadMetadata(archiveStudyDir);
        if (metadata != null) {
            metadata.setHonestBrokerName(brokerName);
            metadata.setHashUidsEnabled(hashUidsEnabled);
            saveMetadata(archiveStudyDir, metadata);
            log.debug("[{}] Updated broker info: broker={}, hashUids={}", aeTitle, brokerName, hashUidsEnabled);
        }
    }

    /**
     * Generate an audit report comparing original and anonymized files.
     * Saves to archive/{date}/study_{uid}/audit_report.json
     *
     * @param aeTitle    The AE Title (route) for this study
     * @param studyUid   The Study Instance UID
     * @param scriptName Name of the anonymization script used
     * @return The generated audit report
     */
    public AnonymizationAuditService.AuditReport generateAuditReport(String aeTitle, String studyUid,
                                                                       String scriptName) throws IOException {
        log.info("[{}] Generating audit report for study {}", aeTitle, studyUid);

        Path archiveStudyDir = getArchiveStudyDir(aeTitle, studyUid);
        Path originalDir = archiveStudyDir.resolve(ORIGINAL_DIR);
        Path anonymizedDir = archiveStudyDir.resolve(ANONYMIZED_DIR);

        if (!Files.exists(originalDir) || !Files.exists(anonymizedDir)) {
            throw new IOException("Both original and anonymized directories must exist for audit report");
        }

        // Generate the audit report
        AnonymizationAuditService.AuditReport report = auditService.generateReport(
                originalDir, anonymizedDir, scriptName);

        // Save to archive
        Path reportFile = archiveStudyDir.resolve(AUDIT_REPORT_FILE);
        objectMapper.writeValue(reportFile.toFile(), report);
        log.debug("[{}] Saved audit report to {}", aeTitle, reportFile);

        // Update metadata with audit info
        ArchiveMetadata metadata = loadMetadata(archiveStudyDir);
        if (metadata != null) {
            metadata.setAuditReportGeneratedAt(LocalDateTime.now());
            metadata.setPhiFieldsModified(report.getPhiFieldsModified());
            metadata.setConformanceIssues(report.getNonConformantFiles());
            saveMetadata(archiveStudyDir, metadata);
        }

        return report;
    }

    /**
     * Save destination status to archive.
     * Saves to archive/{date}/study_{uid}/destinations/{dest}.json
     *
     * @param aeTitle    The AE Title (route) for this study
     * @param studyUid   The Study Instance UID
     * @param destName   Name of the destination
     * @param status     Status information to save
     */
    public void saveDestinationStatus(String aeTitle, String studyUid, String destName,
                                       DestinationStatus status) throws IOException {
        Path archiveStudyDir = getArchiveStudyDir(aeTitle, studyUid);
        Path destDir = archiveStudyDir.resolve(DESTINATIONS_DIR);
        Files.createDirectories(destDir);

        Path destFile = destDir.resolve(destName + ".json");
        objectMapper.writeValue(destFile.toFile(), status);
        log.debug("[{}] Saved destination status for {} to {}", aeTitle, destName, destFile);
    }

    /**
     * Load destination status from archive.
     *
     * @param aeTitle  The AE Title (route) for this study
     * @param studyUid The Study Instance UID
     * @param destName Name of the destination
     * @return The destination status, or null if not found
     */
    public DestinationStatus loadDestinationStatus(String aeTitle, String studyUid, String destName) {
        try {
            Path archiveStudyDir = getArchiveStudyDir(aeTitle, studyUid);
            Path destFile = archiveStudyDir.resolve(DESTINATIONS_DIR).resolve(destName + ".json");

            if (!Files.exists(destFile)) {
                return null;
            }

            return objectMapper.readValue(destFile.toFile(), DestinationStatus.class);
        } catch (IOException e) {
            log.warn("[{}] Failed to load destination status for {}: {}", aeTitle, destName, e.getMessage());
            return null;
        }
    }

    /**
     * Get all destination statuses for a study.
     *
     * @param aeTitle  The AE Title (route) for this study
     * @param studyUid The Study Instance UID
     * @return Map of destination name to status
     */
    public Map<String, DestinationStatus> getAllDestinationStatuses(String aeTitle, String studyUid) {
        Map<String, DestinationStatus> statuses = new HashMap<>();

        try {
            Path archiveStudyDir = getArchiveStudyDir(aeTitle, studyUid);
            Path destDir = archiveStudyDir.resolve(DESTINATIONS_DIR);

            if (!Files.exists(destDir)) {
                return statuses;
            }

            try (Stream<Path> files = Files.list(destDir)) {
                files.filter(p -> p.toString().endsWith(".json"))
                     .forEach(file -> {
                         try {
                             String destName = file.getFileName().toString().replace(".json", "");
                             DestinationStatus status = objectMapper.readValue(file.toFile(), DestinationStatus.class);
                             statuses.put(destName, status);
                         } catch (IOException e) {
                             log.warn("Failed to read destination status from {}: {}", file, e.getMessage());
                         }
                     });
            }
        } catch (IOException e) {
            log.warn("[{}] Failed to list destination statuses: {}", aeTitle, e.getMessage());
        }

        return statuses;
    }

    /**
     * Get the archived study data for retry purposes.
     *
     * @param aeTitle  The AE Title (route) for this study
     * @param studyUid The Study Instance UID
     * @return ArchivedStudy containing paths and metadata, or null if not found
     */
    public ArchivedStudy getArchivedStudy(String aeTitle, String studyUid) {
        try {
            // Try to find the study in archive (may be in different dates)
            Path archiveStudyDir = findArchivedStudyDir(aeTitle, studyUid);
            if (archiveStudyDir == null) {
                return null;
            }

            ArchivedStudy study = new ArchivedStudy();
            study.setStudyUid(studyUid);
            study.setAeTitle(aeTitle);
            study.setArchivePath(archiveStudyDir);

            Path originalDir = archiveStudyDir.resolve(ORIGINAL_DIR);
            Path anonymizedDir = archiveStudyDir.resolve(ANONYMIZED_DIR);

            if (Files.exists(originalDir)) {
                study.setOriginalPath(originalDir);
                study.setOriginalFiles(listDicomFiles(originalDir));
            }

            if (Files.exists(anonymizedDir)) {
                study.setAnonymizedPath(anonymizedDir);
                study.setAnonymizedFiles(listDicomFiles(anonymizedDir));
            }

            // Load metadata
            ArchiveMetadata metadata = loadMetadata(archiveStudyDir);
            if (metadata != null) {
                study.setMetadata(metadata);
            }

            // Load destination statuses
            study.setDestinationStatuses(getAllDestinationStatuses(aeTitle, studyUid));

            // Load audit report if exists
            Path auditFile = archiveStudyDir.resolve(AUDIT_REPORT_FILE);
            if (Files.exists(auditFile)) {
                try {
                    AnonymizationAuditService.AuditReport auditReport =
                            objectMapper.readValue(auditFile.toFile(), AnonymizationAuditService.AuditReport.class);
                    study.setAuditReport(auditReport);
                } catch (IOException e) {
                    log.warn("Failed to load audit report: {}", e.getMessage());
                }
            }

            return study;
        } catch (IOException e) {
            log.error("[{}] Failed to load archived study {}: {}", aeTitle, studyUid, e.getMessage(), e);
            return null;
        }
    }

    /**
     * List all archived studies for an AE Title.
     *
     * @param aeTitle The AE Title (route)
     * @param limit   Maximum number of results
     * @return List of archived study summaries
     */
    public List<ArchivedStudySummary> listArchivedStudies(String aeTitle, int limit) {
        List<ArchivedStudySummary> studies = new ArrayList<>();

        try {
            Path archiveDir = baseDir.resolve(aeTitle).resolve(ARCHIVE_DIR);
            if (!Files.exists(archiveDir)) {
                return studies;
            }

            // Walk through date directories
            try (Stream<Path> dates = Files.list(archiveDir).sorted(Comparator.reverseOrder())) {
                dates.filter(Files::isDirectory)
                     .forEach(dateDir -> {
                         if (studies.size() >= limit) return;

                         try (Stream<Path> studyDirs = Files.list(dateDir)) {
                             studyDirs.filter(Files::isDirectory)
                                      .filter(p -> p.getFileName().toString().startsWith("study_"))
                                      .forEach(studyDir -> {
                                          if (studies.size() >= limit) return;

                                          try {
                                              ArchivedStudySummary summary = createStudySummary(studyDir, aeTitle);
                                              if (summary != null) {
                                                  studies.add(summary);
                                              }
                                          } catch (Exception e) {
                                              log.warn("Failed to create summary for {}: {}", studyDir, e.getMessage());
                                          }
                                      });
                         } catch (IOException e) {
                             log.warn("Failed to list studies in {}: {}", dateDir, e.getMessage());
                         }
                     });
            }
        } catch (IOException e) {
            log.error("[{}] Failed to list archived studies: {}", aeTitle, e.getMessage(), e);
        }

        return studies;
    }

    /**
     * Clean up old archive entries based on retention policy.
     *
     * @param aeTitle       The AE Title (route) to clean
     * @param retentionDays Number of days to retain archives
     * @return Number of studies deleted
     */
    public int cleanupOldArchives(String aeTitle, int retentionDays) {
        log.info("[{}] Cleaning up archives older than {} days", aeTitle, retentionDays);

        int deleted = 0;
        LocalDate cutoffDate = LocalDate.now().minusDays(retentionDays);

        try {
            Path archiveDir = baseDir.resolve(aeTitle).resolve(ARCHIVE_DIR);
            if (!Files.exists(archiveDir)) {
                return 0;
            }

            try (Stream<Path> dates = Files.list(archiveDir)) {
                for (Path dateDir : dates.filter(Files::isDirectory).collect(Collectors.toList())) {
                    String dateName = dateDir.getFileName().toString();
                    try {
                        LocalDate dirDate = LocalDate.parse(dateName, DateTimeFormatter.ISO_LOCAL_DATE);
                        if (dirDate.isBefore(cutoffDate)) {
                            deleted += deleteArchiveDate(dateDir);
                        }
                    } catch (Exception e) {
                        log.warn("Could not parse date directory name: {}", dateName);
                    }
                }
            }
        } catch (IOException e) {
            log.error("[{}] Failed to cleanup old archives: {}", aeTitle, e.getMessage(), e);
        }

        log.info("[{}] Deleted {} archived studies", aeTitle, deleted);
        return deleted;
    }

    // Helper methods

    /**
     * Get the archive directory path for a study (creates date-based structure).
     * Made public to allow DicomRouter to use it for dual-write approach.
     */
    public Path getArchiveStudyDir(String aeTitle, String studyUid) {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String studyDirName = "study_" + sanitizeUid(studyUid);
        return baseDir.resolve(aeTitle).resolve(ARCHIVE_DIR).resolve(dateStr).resolve(studyDirName);
    }

    /**
     * Find an archived study directory (may be in a different date folder).
     */
    private Path findArchivedStudyDir(String aeTitle, String studyUid) throws IOException {
        Path archiveDir = baseDir.resolve(aeTitle).resolve(ARCHIVE_DIR);
        if (!Files.exists(archiveDir)) {
            return null;
        }

        String studyDirName = "study_" + sanitizeUid(studyUid);

        // Search through date directories
        try (Stream<Path> dates = Files.list(archiveDir)) {
            return dates.filter(Files::isDirectory)
                        .map(dateDir -> dateDir.resolve(studyDirName))
                        .filter(Files::exists)
                        .findFirst()
                        .orElse(null);
        }
    }

    /**
     * Sanitize Study UID for use in directory names.
     */
    private String sanitizeUid(String uid) {
        return uid.replaceAll("[^a-zA-Z0-9.-]", "_");
    }

    /**
     * Copy all files from source to destination directory.
     */
    private int copyDirectory(Path source, Path destination) throws IOException {
        if (!Files.exists(source)) {
            return 0;
        }

        int[] count = {0};

        try (Stream<Path> files = Files.walk(source)) {
            files.filter(Files::isRegularFile)
                 .forEach(file -> {
                     try {
                         Path destFile = destination.resolve(file.getFileName());
                         Files.copy(file, destFile, StandardCopyOption.REPLACE_EXISTING);
                         count[0]++;
                     } catch (IOException e) {
                         log.warn("Failed to copy file {}: {}", file, e.getMessage());
                     }
                 });
        }

        return count[0];
    }

    /**
     * List DICOM files in a directory.
     */
    private List<Path> listDicomFiles(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return Collections.emptyList();
        }

        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                        .filter(this::isDicomFile)
                        .collect(Collectors.toList());
        }
    }

    private boolean isDicomFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".dcm") || !name.contains(".");
    }

    /**
     * Save archive metadata.
     */
    private void saveMetadata(Path archiveStudyDir, ArchiveMetadata metadata) throws IOException {
        Path metadataFile = archiveStudyDir.resolve(ARCHIVE_METADATA_FILE);
        objectMapper.writeValue(metadataFile.toFile(), metadata);
    }

    /**
     * Load archive metadata.
     */
    private ArchiveMetadata loadMetadata(Path archiveStudyDir) {
        try {
            Path metadataFile = archiveStudyDir.resolve(ARCHIVE_METADATA_FILE);
            if (!Files.exists(metadataFile)) {
                return null;
            }
            return objectMapper.readValue(metadataFile.toFile(), ArchiveMetadata.class);
        } catch (IOException e) {
            log.warn("Failed to load archive metadata: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Create a study summary from an archive directory.
     */
    private ArchivedStudySummary createStudySummary(Path studyDir, String aeTitle) {
        ArchiveMetadata metadata = loadMetadata(studyDir);

        ArchivedStudySummary summary = new ArchivedStudySummary();
        summary.setArchivePath(studyDir.toString());
        summary.setAeTitle(aeTitle);

        if (metadata != null) {
            summary.setStudyUid(metadata.getStudyUid());
            summary.setArchivedAt(metadata.getArchivedAt());
            summary.setOriginalFileCount(metadata.getOriginalFileCount());
            summary.setAnonymizedFileCount(metadata.getAnonymizedFileCount());
            summary.setAnonymizationScript(metadata.getAnonymizationScript());
            summary.setCallingAeTitle(metadata.getCallingAeTitle());
        } else {
            // Extract study UID from directory name
            String dirName = studyDir.getFileName().toString();
            if (dirName.startsWith("study_")) {
                summary.setStudyUid(dirName.substring(6));
            }
        }

        // Check for original/anonymized directories
        summary.setHasOriginal(Files.exists(studyDir.resolve(ORIGINAL_DIR)));
        summary.setHasAnonymized(Files.exists(studyDir.resolve(ANONYMIZED_DIR)));
        summary.setHasAuditReport(Files.exists(studyDir.resolve(AUDIT_REPORT_FILE)));

        // Get destination statuses
        Map<String, DestinationStatus> destStatuses = getAllDestinationStatuses(aeTitle, summary.getStudyUid());
        summary.setDestinationCount(destStatuses.size());
        summary.setSuccessfulDestinations((int) destStatuses.values().stream()
                .filter(s -> s.getStatus() == DestinationStatusEnum.SUCCESS)
                .count());
        summary.setFailedDestinations((int) destStatuses.values().stream()
                .filter(s -> s.getStatus() == DestinationStatusEnum.FAILED)
                .count());

        return summary;
    }

    /**
     * Delete all studies in a date directory.
     */
    private int deleteArchiveDate(Path dateDir) throws IOException {
        int deleted = 0;

        try (Stream<Path> studies = Files.list(dateDir)) {
            for (Path studyDir : studies.filter(Files::isDirectory).collect(Collectors.toList())) {
                deleteDirectory(studyDir);
                deleted++;
            }
        }

        // Delete the date directory if empty
        if (isDirEmpty(dateDir)) {
            Files.delete(dateDir);
        }

        return deleted;
    }

    /**
     * Recursively delete a directory.
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

    private boolean isDirEmpty(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findFirst().isEmpty();
        }
    }

    // Data classes

    /**
     * Status enum for destinations.
     */
    public enum DestinationStatusEnum {
        PENDING,
        PROCESSING,
        SUCCESS,
        FAILED,
        RETRY_PENDING
    }

    /**
     * Destination status tracking.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DestinationStatus {
        private String destination;
        private DestinationStatusEnum status;
        private String message;
        private int attempts;
        private LocalDateTime lastAttemptAt;
        private LocalDateTime nextRetryAt;
        private long durationMs;
        private int filesTransferred;
        private String errorDetails;

        public String getDestination() { return destination; }
        public void setDestination(String destination) { this.destination = destination; }

        public DestinationStatusEnum getStatus() { return status; }
        public void setStatus(DestinationStatusEnum status) { this.status = status; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public int getAttempts() { return attempts; }
        public void setAttempts(int attempts) { this.attempts = attempts; }

        public LocalDateTime getLastAttemptAt() { return lastAttemptAt; }
        public void setLastAttemptAt(LocalDateTime lastAttemptAt) { this.lastAttemptAt = lastAttemptAt; }

        public LocalDateTime getNextRetryAt() { return nextRetryAt; }
        public void setNextRetryAt(LocalDateTime nextRetryAt) { this.nextRetryAt = nextRetryAt; }

        public long getDurationMs() { return durationMs; }
        public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

        public int getFilesTransferred() { return filesTransferred; }
        public void setFilesTransferred(int filesTransferred) { this.filesTransferred = filesTransferred; }

        public String getErrorDetails() { return errorDetails; }
        public void setErrorDetails(String errorDetails) { this.errorDetails = errorDetails; }
    }

    /**
     * Metadata stored with each archived study.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ArchiveMetadata {
        private String studyUid;
        private String aeTitle;
        private String callingAeTitle;
        private LocalDateTime archivedAt;
        private LocalDateTime anonymizedAt;
        private LocalDateTime auditReportGeneratedAt;
        private int originalFileCount;
        private int anonymizedFileCount;
        private String originalPath;
        private String anonymizationScript;
        private int phiFieldsModified;
        private int conformanceIssues;
        private String honestBrokerName;
        private boolean hashUidsEnabled;

        public String getStudyUid() { return studyUid; }
        public void setStudyUid(String studyUid) { this.studyUid = studyUid; }

        public String getAeTitle() { return aeTitle; }
        public void setAeTitle(String aeTitle) { this.aeTitle = aeTitle; }

        public String getCallingAeTitle() { return callingAeTitle; }
        public void setCallingAeTitle(String callingAeTitle) { this.callingAeTitle = callingAeTitle; }

        public LocalDateTime getArchivedAt() { return archivedAt; }
        public void setArchivedAt(LocalDateTime archivedAt) { this.archivedAt = archivedAt; }

        public LocalDateTime getAnonymizedAt() { return anonymizedAt; }
        public void setAnonymizedAt(LocalDateTime anonymizedAt) { this.anonymizedAt = anonymizedAt; }

        public LocalDateTime getAuditReportGeneratedAt() { return auditReportGeneratedAt; }
        public void setAuditReportGeneratedAt(LocalDateTime auditReportGeneratedAt) { this.auditReportGeneratedAt = auditReportGeneratedAt; }

        public int getOriginalFileCount() { return originalFileCount; }
        public void setOriginalFileCount(int originalFileCount) { this.originalFileCount = originalFileCount; }

        public int getAnonymizedFileCount() { return anonymizedFileCount; }
        public void setAnonymizedFileCount(int anonymizedFileCount) { this.anonymizedFileCount = anonymizedFileCount; }

        public String getOriginalPath() { return originalPath; }
        public void setOriginalPath(String originalPath) { this.originalPath = originalPath; }

        public String getAnonymizationScript() { return anonymizationScript; }
        public void setAnonymizationScript(String anonymizationScript) { this.anonymizationScript = anonymizationScript; }

        public int getPhiFieldsModified() { return phiFieldsModified; }
        public void setPhiFieldsModified(int phiFieldsModified) { this.phiFieldsModified = phiFieldsModified; }

        public int getConformanceIssues() { return conformanceIssues; }
        public void setConformanceIssues(int conformanceIssues) { this.conformanceIssues = conformanceIssues; }

        public String getHonestBrokerName() { return honestBrokerName; }
        public void setHonestBrokerName(String honestBrokerName) { this.honestBrokerName = honestBrokerName; }

        public boolean isHashUidsEnabled() { return hashUidsEnabled; }
        public void setHashUidsEnabled(boolean hashUidsEnabled) { this.hashUidsEnabled = hashUidsEnabled; }
    }

    /**
     * Full archived study data including file paths and metadata.
     */
    public static class ArchivedStudy {
        private String studyUid;
        private String aeTitle;
        private Path archivePath;
        private Path originalPath;
        private Path anonymizedPath;
        private List<Path> originalFiles;
        private List<Path> anonymizedFiles;
        private ArchiveMetadata metadata;
        private Map<String, DestinationStatus> destinationStatuses;
        private AnonymizationAuditService.AuditReport auditReport;

        public String getStudyUid() { return studyUid; }
        public void setStudyUid(String studyUid) { this.studyUid = studyUid; }

        public String getAeTitle() { return aeTitle; }
        public void setAeTitle(String aeTitle) { this.aeTitle = aeTitle; }

        public Path getArchivePath() { return archivePath; }
        public void setArchivePath(Path archivePath) { this.archivePath = archivePath; }

        public Path getOriginalPath() { return originalPath; }
        public void setOriginalPath(Path originalPath) { this.originalPath = originalPath; }

        public Path getAnonymizedPath() { return anonymizedPath; }
        public void setAnonymizedPath(Path anonymizedPath) { this.anonymizedPath = anonymizedPath; }

        public List<Path> getOriginalFiles() { return originalFiles; }
        public void setOriginalFiles(List<Path> originalFiles) { this.originalFiles = originalFiles; }

        public List<Path> getAnonymizedFiles() { return anonymizedFiles; }
        public void setAnonymizedFiles(List<Path> anonymizedFiles) { this.anonymizedFiles = anonymizedFiles; }

        public ArchiveMetadata getMetadata() { return metadata; }
        public void setMetadata(ArchiveMetadata metadata) { this.metadata = metadata; }

        public Map<String, DestinationStatus> getDestinationStatuses() { return destinationStatuses; }
        public void setDestinationStatuses(Map<String, DestinationStatus> destinationStatuses) {
            this.destinationStatuses = destinationStatuses;
        }

        public AnonymizationAuditService.AuditReport getAuditReport() { return auditReport; }
        public void setAuditReport(AnonymizationAuditService.AuditReport auditReport) { this.auditReport = auditReport; }

        /**
         * Check if this study has any failed destinations that could be retried.
         */
        public boolean hasFailedDestinations() {
            if (destinationStatuses == null) return false;
            return destinationStatuses.values().stream()
                    .anyMatch(s -> s.getStatus() == DestinationStatusEnum.FAILED);
        }

        /**
         * Get list of failed destination names.
         */
        public List<String> getFailedDestinationNames() {
            if (destinationStatuses == null) return Collections.emptyList();
            return destinationStatuses.entrySet().stream()
                    .filter(e -> e.getValue().getStatus() == DestinationStatusEnum.FAILED)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
        }
    }

    /**
     * Summary of an archived study for listing purposes.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ArchivedStudySummary {
        private String studyUid;
        private String aeTitle;
        private String archivePath;
        private String callingAeTitle;
        private LocalDateTime archivedAt;
        private int originalFileCount;
        private int anonymizedFileCount;
        private String anonymizationScript;
        private boolean hasOriginal;
        private boolean hasAnonymized;
        private boolean hasAuditReport;
        private int destinationCount;
        private int successfulDestinations;
        private int failedDestinations;

        public String getStudyUid() { return studyUid; }
        public void setStudyUid(String studyUid) { this.studyUid = studyUid; }

        public String getAeTitle() { return aeTitle; }
        public void setAeTitle(String aeTitle) { this.aeTitle = aeTitle; }

        public String getArchivePath() { return archivePath; }
        public void setArchivePath(String archivePath) { this.archivePath = archivePath; }

        public String getCallingAeTitle() { return callingAeTitle; }
        public void setCallingAeTitle(String callingAeTitle) { this.callingAeTitle = callingAeTitle; }

        public LocalDateTime getArchivedAt() { return archivedAt; }
        public void setArchivedAt(LocalDateTime archivedAt) { this.archivedAt = archivedAt; }

        public int getOriginalFileCount() { return originalFileCount; }
        public void setOriginalFileCount(int originalFileCount) { this.originalFileCount = originalFileCount; }

        public int getAnonymizedFileCount() { return anonymizedFileCount; }
        public void setAnonymizedFileCount(int anonymizedFileCount) { this.anonymizedFileCount = anonymizedFileCount; }

        public String getAnonymizationScript() { return anonymizationScript; }
        public void setAnonymizationScript(String anonymizationScript) { this.anonymizationScript = anonymizationScript; }

        public boolean isHasOriginal() { return hasOriginal; }
        public void setHasOriginal(boolean hasOriginal) { this.hasOriginal = hasOriginal; }

        public boolean isHasAnonymized() { return hasAnonymized; }
        public void setHasAnonymized(boolean hasAnonymized) { this.hasAnonymized = hasAnonymized; }

        public boolean isHasAuditReport() { return hasAuditReport; }
        public void setHasAuditReport(boolean hasAuditReport) { this.hasAuditReport = hasAuditReport; }

        public int getDestinationCount() { return destinationCount; }
        public void setDestinationCount(int destinationCount) { this.destinationCount = destinationCount; }

        public int getSuccessfulDestinations() { return successfulDestinations; }
        public void setSuccessfulDestinations(int successfulDestinations) { this.successfulDestinations = successfulDestinations; }

        public int getFailedDestinations() { return failedDestinations; }
        public void setFailedDestinations(int failedDestinations) { this.failedDestinations = failedDestinations; }
    }
}
