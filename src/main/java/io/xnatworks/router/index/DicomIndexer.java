/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.index;

import io.xnatworks.router.store.RouterStore;
import io.xnatworks.router.store.RouterStore.*;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.io.DicomInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * DICOM file indexer that extracts metadata and stores it in the database.
 * Supports both standard DICOM fields and user-defined custom fields.
 */
public class DicomIndexer {
    private static final Logger log = LoggerFactory.getLogger(DicomIndexer.class);

    /**
     * Chunk size for date-range queries on large PACS.
     */
    public enum ChunkSize {
        HOURLY,   // For very large PACS (millions of studies)
        DAILY,    // For large PACS
        WEEKLY,   // Medium PACS
        MONTHLY,  // Default for most PACS
        YEARLY,   // Small archives
        NONE      // No chunking - query all at once
    }

    private static final DateTimeFormatter DICOM_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final RouterStore store;
    private final ExecutorService executor;
    private volatile ReindexJob currentJob;
    private volatile boolean cancelRequested = false;

    // Common DICOM tag name mappings
    private static final Map<String, Integer> TAG_NAME_MAP = new HashMap<>();
    static {
        // Patient level
        TAG_NAME_MAP.put("patientid", Tag.PatientID);
        TAG_NAME_MAP.put("patientname", Tag.PatientName);
        TAG_NAME_MAP.put("patientsname", Tag.PatientName);
        TAG_NAME_MAP.put("patientbirthdate", Tag.PatientBirthDate);
        TAG_NAME_MAP.put("patientsex", Tag.PatientSex);
        TAG_NAME_MAP.put("patientage", Tag.PatientAge);
        TAG_NAME_MAP.put("patientweight", Tag.PatientWeight);

        // Study level
        TAG_NAME_MAP.put("studyinstanceuid", Tag.StudyInstanceUID);
        TAG_NAME_MAP.put("studydate", Tag.StudyDate);
        TAG_NAME_MAP.put("studytime", Tag.StudyTime);
        TAG_NAME_MAP.put("studydescription", Tag.StudyDescription);
        TAG_NAME_MAP.put("accessionnumber", Tag.AccessionNumber);
        TAG_NAME_MAP.put("studyid", Tag.StudyID);
        TAG_NAME_MAP.put("referringphysicianname", Tag.ReferringPhysicianName);
        TAG_NAME_MAP.put("institutionname", Tag.InstitutionName);
        TAG_NAME_MAP.put("institutionaladdress", Tag.InstitutionAddress);
        TAG_NAME_MAP.put("performingphysicianname", Tag.PerformingPhysicianName);
        TAG_NAME_MAP.put("modalitiesinstudy", Tag.ModalitiesInStudy);

        // Series level
        TAG_NAME_MAP.put("seriesinstanceuid", Tag.SeriesInstanceUID);
        TAG_NAME_MAP.put("modality", Tag.Modality);
        TAG_NAME_MAP.put("seriesnumber", Tag.SeriesNumber);
        TAG_NAME_MAP.put("seriesdate", Tag.SeriesDate);
        TAG_NAME_MAP.put("seriestime", Tag.SeriesTime);
        TAG_NAME_MAP.put("seriesdescription", Tag.SeriesDescription);
        TAG_NAME_MAP.put("bodypartexamined", Tag.BodyPartExamined);
        TAG_NAME_MAP.put("protocolname", Tag.ProtocolName);
        TAG_NAME_MAP.put("laterality", Tag.Laterality);

        // Instance level
        TAG_NAME_MAP.put("sopinstanceuid", Tag.SOPInstanceUID);
        TAG_NAME_MAP.put("sopclassuid", Tag.SOPClassUID);
        TAG_NAME_MAP.put("instancenumber", Tag.InstanceNumber);
        TAG_NAME_MAP.put("acquisitionnumber", Tag.AcquisitionNumber);
        TAG_NAME_MAP.put("contentdate", Tag.ContentDate);
        TAG_NAME_MAP.put("contenttime", Tag.ContentTime);

        // Equipment
        TAG_NAME_MAP.put("manufacturer", Tag.Manufacturer);
        TAG_NAME_MAP.put("manufacturermodelname", Tag.ManufacturerModelName);
        TAG_NAME_MAP.put("stationname", Tag.StationName);
        TAG_NAME_MAP.put("softwareversions", Tag.SoftwareVersions);
        TAG_NAME_MAP.put("deviceserialnumber", Tag.DeviceSerialNumber);

        // Image specific
        TAG_NAME_MAP.put("rows", Tag.Rows);
        TAG_NAME_MAP.put("columns", Tag.Columns);
        TAG_NAME_MAP.put("bitsstored", Tag.BitsStored);
        TAG_NAME_MAP.put("pixelspacing", Tag.PixelSpacing);
        TAG_NAME_MAP.put("slicethickness", Tag.SliceThickness);
        TAG_NAME_MAP.put("imagetype", Tag.ImageType);
        TAG_NAME_MAP.put("windowcenter", Tag.WindowCenter);
        TAG_NAME_MAP.put("windowwidth", Tag.WindowWidth);

        // CT specific
        TAG_NAME_MAP.put("kvp", Tag.KVP);
        TAG_NAME_MAP.put("exposuretime", Tag.ExposureTime);
        TAG_NAME_MAP.put("tubecurrent", Tag.XRayTubeCurrent);
        TAG_NAME_MAP.put("convolutionkernel", Tag.ConvolutionKernel);

        // MR specific
        TAG_NAME_MAP.put("magneticfieldstrength", Tag.MagneticFieldStrength);
        TAG_NAME_MAP.put("repetitiontime", Tag.RepetitionTime);
        TAG_NAME_MAP.put("echotime", Tag.EchoTime);
        TAG_NAME_MAP.put("flipangle", Tag.FlipAngle);
        TAG_NAME_MAP.put("sequencename", Tag.SequenceName);
        TAG_NAME_MAP.put("scanningsequence", Tag.ScanningSequence);
    }

    public DicomIndexer(RouterStore store) {
        this.store = store;
        this.executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "dicom-indexer");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Index a single DICOM file.
     */
    public void indexFile(File dicomFile, String sourceRoute) throws IOException {
        if (!dicomFile.exists() || !dicomFile.isFile()) {
            throw new IOException("File does not exist: " + dicomFile);
        }

        Attributes attrs;
        try (DicomInputStream dis = new DicomInputStream(dicomFile)) {
            dis.readFileMetaInformation();
            attrs = dis.readDataset();
        }

        String studyUid = attrs.getString(Tag.StudyInstanceUID);
        String seriesUid = attrs.getString(Tag.SeriesInstanceUID);
        String sopInstanceUid = attrs.getString(Tag.SOPInstanceUID);

        if (studyUid == null || seriesUid == null || sopInstanceUid == null) {
            log.warn("Missing UIDs in file: {}", dicomFile);
            return;
        }

        // Index instance
        IndexedInstance instance = new IndexedInstance();
        instance.sopInstanceUid = sopInstanceUid;
        instance.seriesUid = seriesUid;
        instance.sopClassUid = attrs.getString(Tag.SOPClassUID);
        instance.instanceNumber = attrs.getInt(Tag.InstanceNumber, 0);
        instance.filePath = dicomFile.getAbsolutePath();
        instance.fileSize = dicomFile.length();
        instance.fileHash = computeFileHash(dicomFile);
        store.upsertInstance(instance);

        // Index series (will update if exists)
        IndexedSeries series = new IndexedSeries();
        series.seriesUid = seriesUid;
        series.studyUid = studyUid;
        series.modality = attrs.getString(Tag.Modality);
        series.seriesNumber = attrs.getInt(Tag.SeriesNumber, 0);
        series.seriesDescription = attrs.getString(Tag.SeriesDescription);
        series.bodyPart = attrs.getString(Tag.BodyPartExamined);
        // Instance count will be updated when we aggregate
        store.upsertSeries(series);

        // Index study (will update if exists)
        IndexedStudy study = new IndexedStudy();
        study.studyUid = studyUid;
        study.patientId = attrs.getString(Tag.PatientID);
        study.patientName = attrs.getString(Tag.PatientName);
        study.patientSex = attrs.getString(Tag.PatientSex);
        study.studyDate = attrs.getString(Tag.StudyDate);
        study.studyTime = attrs.getString(Tag.StudyTime);
        study.accessionNumber = attrs.getString(Tag.AccessionNumber);
        study.studyDescription = attrs.getString(Tag.StudyDescription);
        study.modalities = attrs.getString(Tag.Modality);  // Will be aggregated later
        study.institutionName = attrs.getString(Tag.InstitutionName);
        study.referringPhysician = attrs.getString(Tag.ReferringPhysicianName);
        study.sourceRoute = sourceRoute;
        store.upsertStudy(study);

        // Index custom fields
        indexCustomFields(attrs, studyUid, seriesUid, sopInstanceUid);
    }

    /**
     * Index custom fields defined by the user.
     */
    private void indexCustomFields(Attributes attrs, String studyUid, String seriesUid, String sopInstanceUid) {
        List<CustomField> customFields = store.getEnabledCustomFields();

        for (CustomField field : customFields) {
            int tag = parseTag(field.dicomTag);
            if (tag == -1) {
                log.debug("Unknown tag: {}", field.dicomTag);
                continue;
            }

            String value = getTagValue(attrs, tag, field.fieldType);
            if (value == null || value.isEmpty()) {
                continue;
            }

            // Store based on level
            String entityUid;
            switch (field.level.toLowerCase()) {
                case "series":
                    entityUid = seriesUid;
                    break;
                case "instance":
                    entityUid = sopInstanceUid;
                    break;
                case "study":
                default:
                    entityUid = studyUid;
                    break;
            }

            store.setCustomFieldValue(field.id, entityUid, value);
        }
    }

    /**
     * Index a list of DICOM files.
     * @param files list of DICOM files to index
     * @param sourceRoute the source route (AE title) for tracking
     */
    public void indexFiles(java.util.List<File> files, String sourceRoute) {
        for (File file : files) {
            try {
                if (isDicomFile(file)) {
                    indexFile(file, sourceRoute);
                }
            } catch (Exception e) {
                log.warn("Failed to index {}: {}", file.getName(), e.getMessage());
            }
        }
    }

    /**
     * Index all DICOM files in a directory.
     */
    public void indexDirectory(File directory, String sourceRoute) throws IOException {
        if (!directory.exists() || !directory.isDirectory()) {
            throw new IOException("Directory does not exist: " + directory);
        }

        try (Stream<Path> paths = Files.walk(directory.toPath())) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> isDicomFile(p.toFile()))
                 .forEach(p -> {
                     try {
                         indexFile(p.toFile(), sourceRoute);
                     } catch (Exception e) {
                         log.warn("Failed to index {}: {}", p, e.getMessage());
                     }
                 });
        }

        // Update aggregated counts
        updateStudyAggregates();
    }

    /**
     * Start a full reindex of all DICOM files in the data directory.
     */
    public ReindexJob startReindex(String baseDir) {
        if (currentJob != null && "running".equals(currentJob.status)) {
            log.warn("Reindex already in progress");
            return currentJob;
        }

        ReindexJob job = store.createReindexJob();
        if (job == null) {
            log.error("Failed to create reindex job");
            return null;
        }

        currentJob = job;
        resetCancelFlag();

        executor.submit(() -> {
            try {
                runReindex(job.id, baseDir);
            } catch (Exception e) {
                log.error("Reindex failed: {}", e.getMessage(), e);
                store.updateReindexJob(job.id, "failed", 0, 0, 0, e.getMessage());
            }
        });

        return job;
    }

    /**
     * Run the reindex process.
     */
    private void runReindex(long jobId, String baseDir) {
        log.info("Starting reindex from: {}", baseDir);

        // Find all DICOM files
        List<Path> dicomFiles = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(Paths.get(baseDir))) {
            dicomFiles = paths.filter(Files::isRegularFile)
                              .filter(p -> isDicomFile(p.toFile()))
                              .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Failed to scan directory: {}", e.getMessage(), e);
            store.updateReindexJob(jobId, "failed", 0, 0, 0, "Failed to scan: " + e.getMessage());
            return;
        }

        int totalFiles = dicomFiles.size();
        log.info("Found {} DICOM files to index", totalFiles);

        store.updateReindexJob(jobId, "running", totalFiles, 0, 0, null);

        // Clear existing index if this is a full reindex
        store.clearIndex();

        AtomicInteger processed = new AtomicInteger(0);
        AtomicInteger errors = new AtomicInteger(0);

        // Process files in parallel
        int batchSize = 100;
        for (int i = 0; i < dicomFiles.size(); i += batchSize) {
            // Check for cancellation
            if (cancelRequested) {
                log.info("Reindex cancelled at {}/{} files", processed.get(), totalFiles);
                store.updateReindexJob(jobId, "cancelled", totalFiles, processed.get(), errors.get(), "Cancelled by user");
                currentJob = null;
                return;
            }

            int end = Math.min(i + batchSize, dicomFiles.size());
            List<Path> batch = dicomFiles.subList(i, end);

            List<CompletableFuture<Void>> futures = batch.stream()
                .map(path -> CompletableFuture.runAsync(() -> {
                    if (cancelRequested) return; // Skip if cancelled
                    try {
                        // Determine source route from path
                        String sourceRoute = extractSourceRoute(path, baseDir);
                        indexFile(path.toFile(), sourceRoute);
                        processed.incrementAndGet();
                    } catch (Exception e) {
                        log.debug("Failed to index {}: {}", path, e.getMessage());
                        errors.incrementAndGet();
                    }
                }, executor))
                .collect(Collectors.toList());

            // Wait for batch to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // Update progress
            store.updateReindexJob(jobId, "running", totalFiles, processed.get(), errors.get(), null);

            log.info("Reindex progress: {}/{} files ({} errors)", processed.get(), totalFiles, errors.get());
        }

        // Final cancellation check
        if (cancelRequested) {
            log.info("Reindex cancelled at {}/{} files", processed.get(), totalFiles);
            store.updateReindexJob(jobId, "cancelled", totalFiles, processed.get(), errors.get(), "Cancelled by user");
            currentJob = null;
            return;
        }

        // Update aggregates
        updateStudyAggregates();

        // Mark as completed
        store.updateReindexJob(jobId, "completed", totalFiles, processed.get(), errors.get(), null);
        currentJob = null;

        log.info("Reindex completed: {} files processed, {} errors", processed.get(), errors.get());
    }

    /**
     * Start indexing from a file-based destination.
     *
     * @param destinationName The name of the destination
     * @param path The file path of the destination
     * @param clearExisting Whether to clear existing index data before indexing
     * @return The reindex job
     */
    public ReindexJob startIndexFromFileDestination(String destinationName, String path, boolean clearExisting) {
        if (currentJob != null && "running".equals(currentJob.status)) {
            log.warn("Reindex already in progress");
            return currentJob;
        }

        ReindexJob job = store.createReindexJob();
        if (job == null) {
            log.error("Failed to create reindex job");
            return null;
        }

        currentJob = job;
        resetCancelFlag();

        executor.submit(() -> {
            try {
                runIndexFromFileDestination(job.id, destinationName, path, clearExisting);
            } catch (Exception e) {
                log.error("Index from file destination failed: {}", e.getMessage(), e);
                store.updateReindexJob(job.id, "failed", 0, 0, 0, e.getMessage());
            }
        });

        return job;
    }

    /**
     * Run indexing from a file-based destination.
     */
    private void runIndexFromFileDestination(long jobId, String destinationName, String path, boolean clearExisting) {
        log.info("Starting index from file destination '{}' at: {}", destinationName, path);

        File destDir = new File(path);
        if (!destDir.exists() || !destDir.isDirectory()) {
            String error = "Destination path does not exist or is not a directory: " + path;
            log.error(error);
            store.updateReindexJob(jobId, "failed", 0, 0, 0, error);
            return;
        }

        // Find all DICOM files
        List<Path> dicomFiles = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(destDir.toPath())) {
            dicomFiles = paths.filter(Files::isRegularFile)
                              .filter(p -> isDicomFile(p.toFile()))
                              .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Failed to scan destination: {}", e.getMessage(), e);
            store.updateReindexJob(jobId, "failed", 0, 0, 0, "Failed to scan: " + e.getMessage());
            return;
        }

        int totalFiles = dicomFiles.size();
        log.info("Found {} DICOM files in destination '{}'", totalFiles, destinationName);

        if (totalFiles == 0) {
            store.updateReindexJob(jobId, "completed", 0, 0, 0, "No DICOM files found");
            currentJob = null;
            return;
        }

        store.updateReindexJob(jobId, "running", totalFiles, 0, 0, null);

        // Clear existing index if requested
        if (clearExisting) {
            store.clearIndex();
        }

        AtomicInteger processed = new AtomicInteger(0);
        AtomicInteger errors = new AtomicInteger(0);

        // Process files in parallel
        int batchSize = 100;
        for (int i = 0; i < dicomFiles.size(); i += batchSize) {
            // Check for cancellation
            if (cancelRequested) {
                log.info("Index cancelled for '{}' at {}/{} files", destinationName, processed.get(), totalFiles);
                store.updateReindexJob(jobId, "cancelled", totalFiles, processed.get(), errors.get(), "Cancelled by user");
                currentJob = null;
                return;
            }

            int end = Math.min(i + batchSize, dicomFiles.size());
            List<Path> batch = dicomFiles.subList(i, end);

            List<CompletableFuture<Void>> futures = batch.stream()
                .map(filePath -> CompletableFuture.runAsync(() -> {
                    if (cancelRequested) return; // Skip if cancelled
                    try {
                        indexFile(filePath.toFile(), destinationName);
                        processed.incrementAndGet();
                    } catch (Exception e) {
                        log.debug("Failed to index {}: {}", filePath, e.getMessage());
                        errors.incrementAndGet();
                    }
                }, executor))
                .collect(Collectors.toList());

            // Wait for batch to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // Update progress
            store.updateReindexJob(jobId, "running", totalFiles, processed.get(), errors.get(), null);

            log.info("Index progress for '{}': {}/{} files ({} errors)",
                    destinationName, processed.get(), totalFiles, errors.get());
        }

        // Final cancellation check
        if (cancelRequested) {
            log.info("Index cancelled for '{}' at {}/{} files", destinationName, processed.get(), totalFiles);
            store.updateReindexJob(jobId, "cancelled", totalFiles, processed.get(), errors.get(), "Cancelled by user");
            currentJob = null;
            return;
        }

        // Update aggregates
        updateStudyAggregates();

        // Mark as completed
        store.updateReindexJob(jobId, "completed", totalFiles, processed.get(), errors.get(), null);
        currentJob = null;

        log.info("Index from file destination '{}' completed: {} files processed, {} errors",
                destinationName, processed.get(), errors.get());
    }

    /**
     * Index from a DICOM AE destination using C-FIND queries.
     * This indexes the metadata without retrieving the actual files.
     *
     * @param destinationName The name of the destination
     * @param host The DICOM host
     * @param port The DICOM port
     * @param calledAeTitle The called AE title
     * @param callingAeTitle The calling AE title
     * @param clearExisting Whether to clear existing index data
     * @return The reindex job
     */
    public ReindexJob startIndexFromDicomDestination(String destinationName, String host, int port,
                                                      String calledAeTitle, String callingAeTitle,
                                                      boolean clearExisting) {
        return startIndexFromDicomDestination(destinationName, host, port, calledAeTitle,
                callingAeTitle, clearExisting, null, null, ChunkSize.NONE);
    }

    /**
     * Index from a DICOM AE destination using C-FIND queries with date range and chunking.
     * This indexes the metadata without retrieving the actual files.
     *
     * @param destinationName The name of the destination
     * @param host The DICOM host
     * @param port The DICOM port
     * @param calledAeTitle The called AE title
     * @param callingAeTitle The calling AE title
     * @param clearExisting Whether to clear existing index data
     * @param studyDateFrom Start date for study date filter (YYYYMMDD format, null for no filter)
     * @param studyDateTo End date for study date filter (YYYYMMDD format, null for no filter)
     * @param chunkSize How to chunk the date range for large PACS
     * @return The reindex job
     */
    public ReindexJob startIndexFromDicomDestination(String destinationName, String host, int port,
                                                      String calledAeTitle, String callingAeTitle,
                                                      boolean clearExisting,
                                                      String studyDateFrom, String studyDateTo,
                                                      ChunkSize chunkSize) {
        if (currentJob != null && "running".equals(currentJob.status)) {
            log.warn("Reindex already in progress");
            return currentJob;
        }

        ReindexJob job = store.createReindexJob();
        if (job == null) {
            log.error("Failed to create reindex job");
            return null;
        }

        currentJob = job;
        resetCancelFlag();

        executor.submit(() -> {
            try {
                runIndexFromDicomDestination(job.id, destinationName, host, port,
                        calledAeTitle, callingAeTitle, clearExisting,
                        studyDateFrom, studyDateTo, chunkSize);
            } catch (Exception e) {
                log.error("Index from DICOM destination failed: {}", e.getMessage(), e);
                store.updateReindexJob(job.id, "failed", 0, 0, 0, e.getMessage());
            }
        });

        return job;
    }

    /**
     * Run C-FIND queries to index from a DICOM destination with date-range chunking support.
     */
    private void runIndexFromDicomDestination(long jobId, String destinationName, String host, int port,
                                               String calledAeTitle, String callingAeTitle, boolean clearExisting,
                                               String studyDateFrom, String studyDateTo, ChunkSize chunkSize) {
        log.info("Starting index from DICOM destination '{}' at {}:{} (AE: {}), dateRange: {}-{}, chunk: {}",
                destinationName, host, port, calledAeTitle, studyDateFrom, studyDateTo, chunkSize);

        store.updateReindexJob(jobId, "running", 0, 0, 0, "Initializing...");

        if (clearExisting) {
            store.clearIndex();
        }

        AtomicInteger studiesFound = new AtomicInteger(0);
        AtomicInteger errors = new AtomicInteger(0);

        try {
            // Generate date chunks if chunking is enabled and dates are provided
            List<String[]> dateChunks = generateDateChunks(studyDateFrom, studyDateTo, chunkSize);

            if (dateChunks.isEmpty()) {
                // No chunking - single query
                dateChunks.add(new String[]{studyDateFrom, studyDateTo});
            }

            int totalChunks = dateChunks.size();
            int currentChunk = 0;

            for (String[] chunk : dateChunks) {
                currentChunk++;
                String chunkFrom = chunk[0];
                String chunkTo = chunk[1];

                // Check for cancellation
                if (cancelRequested) {
                    log.info("Index cancelled for '{}' at chunk {}/{}", destinationName, currentChunk, totalChunks);
                    store.updateReindexJob(jobId, "cancelled", 0, studiesFound.get(), errors.get(), "Cancelled by user");
                    currentJob = null;
                    return;
                }

                String chunkLabel = formatChunkLabel(chunkFrom, chunkTo);
                String progressMsg = totalChunks > 1 ?
                        String.format("Querying chunk %d/%d: %s", currentChunk, totalChunks, chunkLabel) :
                        "Querying studies...";
                store.updateReindexJob(jobId, "running", 0, studiesFound.get(), errors.get(), progressMsg);

                log.info("Querying chunk {}/{}: {} to {}", currentChunk, totalChunks, chunkFrom, chunkTo);

                // Perform C-FIND for this date chunk
                List<Attributes> studies = performStudyFind(host, port, calledAeTitle, callingAeTitle, chunkFrom, chunkTo);
                log.info("Found {} studies in chunk {}", studies.size(), chunkLabel);

                for (Attributes studyAttrs : studies) {
                    // Check for cancellation
                    if (cancelRequested) {
                        log.info("Index cancelled for '{}' at {}/{} studies", destinationName, studiesFound.get(), studies.size());
                        store.updateReindexJob(jobId, "cancelled", 0, studiesFound.get(), errors.get(), "Cancelled by user");
                        currentJob = null;
                        return;
                    }

                    try {
                        String studyUid = studyAttrs.getString(Tag.StudyInstanceUID);
                        if (studyUid == null) continue;

                        // Index study from C-FIND results
                        IndexedStudy study = new IndexedStudy();
                        study.studyUid = studyUid;
                        study.patientId = studyAttrs.getString(Tag.PatientID);
                        study.patientName = studyAttrs.getString(Tag.PatientName);
                        study.patientSex = studyAttrs.getString(Tag.PatientSex);
                        study.studyDate = studyAttrs.getString(Tag.StudyDate);
                        study.studyTime = studyAttrs.getString(Tag.StudyTime);
                        study.accessionNumber = studyAttrs.getString(Tag.AccessionNumber);
                        study.studyDescription = studyAttrs.getString(Tag.StudyDescription);
                        study.modalities = studyAttrs.getString(Tag.ModalitiesInStudy);
                        study.institutionName = studyAttrs.getString(Tag.InstitutionName);
                        study.referringPhysician = studyAttrs.getString(Tag.ReferringPhysicianName);
                        study.sourceRoute = destinationName;
                        study.seriesCount = studyAttrs.getInt(Tag.NumberOfStudyRelatedSeries, 0);
                        study.instanceCount = studyAttrs.getInt(Tag.NumberOfStudyRelatedInstances, 0);
                        store.upsertStudy(study);

                        // Optionally query series level (skip if cancelled)
                        if (!cancelRequested) {
                            List<Attributes> seriesList = performSeriesFind(host, port, calledAeTitle,
                                    callingAeTitle, studyUid);
                            for (Attributes seriesAttrs : seriesList) {
                                if (cancelRequested) break;
                                String seriesUid = seriesAttrs.getString(Tag.SeriesInstanceUID);
                                if (seriesUid == null) continue;

                                IndexedSeries series = new IndexedSeries();
                                series.seriesUid = seriesUid;
                                series.studyUid = studyUid;
                                series.modality = seriesAttrs.getString(Tag.Modality);
                                series.seriesNumber = seriesAttrs.getInt(Tag.SeriesNumber, 0);
                                series.seriesDescription = seriesAttrs.getString(Tag.SeriesDescription);
                                series.bodyPart = seriesAttrs.getString(Tag.BodyPartExamined);
                                series.instanceCount = seriesAttrs.getInt(Tag.NumberOfSeriesRelatedInstances, 0);
                                store.upsertSeries(series);
                            }
                        }

                        studiesFound.incrementAndGet();
                        String status = totalChunks > 1 ?
                                String.format("Chunk %d/%d: %s - %d studies", currentChunk, totalChunks, chunkLabel, studiesFound.get()) :
                                null;
                        store.updateReindexJob(jobId, "running", 0, studiesFound.get(), errors.get(), status);

                    } catch (Exception e) {
                        log.debug("Failed to index study: {}", e.getMessage());
                        errors.incrementAndGet();
                    }
                }
            }

            // Final cancellation check
            if (cancelRequested) {
                log.info("Index cancelled for '{}' at {} studies", destinationName, studiesFound.get());
                store.updateReindexJob(jobId, "cancelled", 0, studiesFound.get(), errors.get(), "Cancelled by user");
                currentJob = null;
                return;
            }

            // Update aggregates
            updateStudyAggregates();

            store.updateReindexJob(jobId, "completed", studiesFound.get(), studiesFound.get(), errors.get(), null);
            log.info("Index from DICOM destination '{}' completed: {} studies indexed, {} errors",
                    destinationName, studiesFound.get(), errors.get());

        } catch (Exception e) {
            log.error("Failed to query DICOM destination: {}", e.getMessage(), e);
            store.updateReindexJob(jobId, "failed", 0, studiesFound.get(), errors.get(),
                    "Query failed: " + e.getMessage());
        } finally {
            currentJob = null;
        }
    }

    // ========================================================================
    // Date Range Helper Methods
    // ========================================================================

    /**
     * Build a DICOM date range string for C-FIND queries.
     * DICOM date range syntax: "YYYYMMDD-YYYYMMDD", "YYYYMMDD-", "-YYYYMMDD"
     *
     * @param from Start date in YYYYMMDD format, or null
     * @param to End date in YYYYMMDD format, or null
     * @return Date range string or null if both are null
     */
    private String buildDicomDateRange(String from, String to) {
        if (from != null && to != null) {
            return from + "-" + to;
        } else if (from != null) {
            return from + "-";
        } else if (to != null) {
            return "-" + to;
        }
        return null;
    }

    /**
     * Generate date chunks for querying large PACS.
     *
     * @param from Start date in YYYYMMDD format
     * @param to End date in YYYYMMDD format
     * @param chunkSize How to chunk the range
     * @return List of [from, to] date pairs for each chunk
     */
    private List<String[]> generateDateChunks(String from, String to, ChunkSize chunkSize) {
        List<String[]> chunks = new ArrayList<>();

        if (chunkSize == ChunkSize.NONE || from == null || to == null) {
            return chunks; // No chunking
        }

        try {
            LocalDate startDate = LocalDate.parse(from, DICOM_DATE_FORMAT);
            LocalDate endDate = LocalDate.parse(to, DICOM_DATE_FORMAT);

            if (startDate.isAfter(endDate)) {
                log.warn("Start date {} is after end date {}, swapping", from, to);
                LocalDate temp = startDate;
                startDate = endDate;
                endDate = temp;
            }

            LocalDate current = startDate;
            while (!current.isAfter(endDate)) {
                LocalDate chunkEnd;
                switch (chunkSize) {
                    case HOURLY:
                        // For hourly, we still chunk by day since StudyDate is day-level
                        chunkEnd = current;
                        break;
                    case DAILY:
                        chunkEnd = current;
                        break;
                    case WEEKLY:
                        chunkEnd = current.plusWeeks(1).minusDays(1);
                        break;
                    case MONTHLY:
                        chunkEnd = current.plusMonths(1).minusDays(1);
                        break;
                    case YEARLY:
                        chunkEnd = current.plusYears(1).minusDays(1);
                        break;
                    default:
                        chunkEnd = endDate;
                }

                if (chunkEnd.isAfter(endDate)) {
                    chunkEnd = endDate;
                }

                chunks.add(new String[]{
                        current.format(DICOM_DATE_FORMAT),
                        chunkEnd.format(DICOM_DATE_FORMAT)
                });

                current = chunkEnd.plusDays(1);
            }

            log.info("Generated {} date chunks from {} to {} (chunk size: {})",
                    chunks.size(), from, to, chunkSize);

        } catch (Exception e) {
            log.error("Failed to parse dates for chunking: {} to {}", from, to, e);
        }

        return chunks;
    }

    /**
     * Format a human-readable label for a date chunk.
     */
    private String formatChunkLabel(String from, String to) {
        if (from == null && to == null) {
            return "All dates";
        }
        if (from != null && to != null) {
            if (from.equals(to)) {
                // Same day
                return formatDisplayDate(from);
            }
            return formatDisplayDate(from) + " - " + formatDisplayDate(to);
        }
        if (from != null) {
            return formatDisplayDate(from) + " onwards";
        }
        return "Up to " + formatDisplayDate(to);
    }

    /**
     * Format a YYYYMMDD date as YYYY-MM-DD for display.
     */
    private String formatDisplayDate(String dicomDate) {
        if (dicomDate == null || dicomDate.length() != 8) {
            return dicomDate;
        }
        return dicomDate.substring(0, 4) + "-" + dicomDate.substring(4, 6) + "-" + dicomDate.substring(6, 8);
    }

    // ========================================================================
    // C-FIND Methods
    // ========================================================================

    /**
     * Perform a study-level C-FIND (no date filter).
     */
    private List<Attributes> performStudyFind(String host, int port, String calledAeTitle, String callingAeTitle)
            throws Exception {
        return performStudyFind(host, port, calledAeTitle, callingAeTitle, null, null);
    }

    /**
     * Perform a study-level C-FIND with optional date range filter.
     *
     * @param studyDateFrom Start date in YYYYMMDD format, or null for no start filter
     * @param studyDateTo End date in YYYYMMDD format, or null for no end filter
     */
    private List<Attributes> performStudyFind(String host, int port, String calledAeTitle, String callingAeTitle,
                                               String studyDateFrom, String studyDateTo)
            throws Exception {
        List<Attributes> results = new ArrayList<>();

        // Build study-level query
        Attributes query = new Attributes();
        query.setString(Tag.QueryRetrieveLevel, org.dcm4che3.data.VR.CS, "STUDY");
        query.setNull(Tag.StudyInstanceUID, org.dcm4che3.data.VR.UI);
        query.setNull(Tag.PatientID, org.dcm4che3.data.VR.LO);
        query.setNull(Tag.PatientName, org.dcm4che3.data.VR.PN);
        query.setNull(Tag.PatientSex, org.dcm4che3.data.VR.CS);

        // Apply date filter if provided (DICOM date range syntax: "YYYYMMDD-YYYYMMDD")
        String dateRange = buildDicomDateRange(studyDateFrom, studyDateTo);
        if (dateRange != null) {
            query.setString(Tag.StudyDate, org.dcm4che3.data.VR.DA, dateRange);
            log.debug("C-FIND with StudyDate filter: {}", dateRange);
        } else {
            query.setNull(Tag.StudyDate, org.dcm4che3.data.VR.DA);
        }

        query.setNull(Tag.StudyTime, org.dcm4che3.data.VR.TM);
        query.setNull(Tag.AccessionNumber, org.dcm4che3.data.VR.SH);
        query.setNull(Tag.StudyDescription, org.dcm4che3.data.VR.LO);
        query.setNull(Tag.ModalitiesInStudy, org.dcm4che3.data.VR.CS);
        query.setNull(Tag.InstitutionName, org.dcm4che3.data.VR.LO);
        query.setNull(Tag.ReferringPhysicianName, org.dcm4che3.data.VR.PN);
        query.setNull(Tag.NumberOfStudyRelatedSeries, org.dcm4che3.data.VR.IS);
        query.setNull(Tag.NumberOfStudyRelatedInstances, org.dcm4che3.data.VR.IS);

        // Use DCM4CHE's FindSCU
        org.dcm4che3.net.Device device = new org.dcm4che3.net.Device("INDEXER");
        org.dcm4che3.net.ApplicationEntity ae = new org.dcm4che3.net.ApplicationEntity(callingAeTitle);
        org.dcm4che3.net.Connection conn = new org.dcm4che3.net.Connection();
        device.addApplicationEntity(ae);
        device.addConnection(conn);
        ae.addConnection(conn);

        org.dcm4che3.net.Connection remoteConn = new org.dcm4che3.net.Connection();
        remoteConn.setHostname(host);
        remoteConn.setPort(port);

        org.dcm4che3.net.pdu.AAssociateRQ rq = new org.dcm4che3.net.pdu.AAssociateRQ();
        rq.setCalledAET(calledAeTitle);
        rq.addPresentationContext(new org.dcm4che3.net.pdu.PresentationContext(
                1, org.dcm4che3.data.UID.StudyRootQueryRetrieveInformationModelFind,
                org.dcm4che3.data.UID.ImplicitVRLittleEndian));

        java.util.concurrent.ExecutorService executorService = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        device.setExecutor(executorService);
        device.setScheduledExecutor((java.util.concurrent.ScheduledExecutorService) executorService);

        try {
            org.dcm4che3.net.Association as = ae.connect(remoteConn, rq);

            org.dcm4che3.net.DimseRSPHandler handler = new org.dcm4che3.net.DimseRSPHandler(as.nextMessageID()) {
                @Override
                public void onDimseRSP(org.dcm4che3.net.Association as, Attributes cmd, Attributes data) {
                    if (data != null) {
                        results.add(data);
                    }
                }
            };

            as.cfind(org.dcm4che3.data.UID.StudyRootQueryRetrieveInformationModelFind,
                    org.dcm4che3.net.Priority.NORMAL, query, null, handler);

            as.waitForOutstandingRSP();
            as.release();
        } finally {
            executorService.shutdown();
        }

        return results;
    }

    /**
     * Perform a series-level C-FIND for a specific study.
     */
    private List<Attributes> performSeriesFind(String host, int port, String calledAeTitle,
                                                String callingAeTitle, String studyUid) throws Exception {
        List<Attributes> results = new ArrayList<>();

        Attributes query = new Attributes();
        query.setString(Tag.QueryRetrieveLevel, org.dcm4che3.data.VR.CS, "SERIES");
        query.setString(Tag.StudyInstanceUID, org.dcm4che3.data.VR.UI, studyUid);
        query.setNull(Tag.SeriesInstanceUID, org.dcm4che3.data.VR.UI);
        query.setNull(Tag.Modality, org.dcm4che3.data.VR.CS);
        query.setNull(Tag.SeriesNumber, org.dcm4che3.data.VR.IS);
        query.setNull(Tag.SeriesDescription, org.dcm4che3.data.VR.LO);
        query.setNull(Tag.BodyPartExamined, org.dcm4che3.data.VR.CS);
        query.setNull(Tag.NumberOfSeriesRelatedInstances, org.dcm4che3.data.VR.IS);

        org.dcm4che3.net.Device device = new org.dcm4che3.net.Device("INDEXER");
        org.dcm4che3.net.ApplicationEntity ae = new org.dcm4che3.net.ApplicationEntity(callingAeTitle);
        org.dcm4che3.net.Connection conn = new org.dcm4che3.net.Connection();
        device.addApplicationEntity(ae);
        device.addConnection(conn);
        ae.addConnection(conn);

        org.dcm4che3.net.Connection remoteConn = new org.dcm4che3.net.Connection();
        remoteConn.setHostname(host);
        remoteConn.setPort(port);

        org.dcm4che3.net.pdu.AAssociateRQ rq = new org.dcm4che3.net.pdu.AAssociateRQ();
        rq.setCalledAET(calledAeTitle);
        rq.addPresentationContext(new org.dcm4che3.net.pdu.PresentationContext(
                1, org.dcm4che3.data.UID.StudyRootQueryRetrieveInformationModelFind,
                org.dcm4che3.data.UID.ImplicitVRLittleEndian));

        java.util.concurrent.ExecutorService executorService = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        device.setExecutor(executorService);
        device.setScheduledExecutor((java.util.concurrent.ScheduledExecutorService) executorService);

        try {
            org.dcm4che3.net.Association as = ae.connect(remoteConn, rq);

            org.dcm4che3.net.DimseRSPHandler handler = new org.dcm4che3.net.DimseRSPHandler(as.nextMessageID()) {
                @Override
                public void onDimseRSP(org.dcm4che3.net.Association as, Attributes cmd, Attributes data) {
                    if (data != null) {
                        results.add(data);
                    }
                }
            };

            as.cfind(org.dcm4che3.data.UID.StudyRootQueryRetrieveInformationModelFind,
                    org.dcm4che3.net.Priority.NORMAL, query, null, handler);

            as.waitForOutstandingRSP();
            as.release();
        } finally {
            executorService.shutdown();
        }

        return results;
    }

    /**
     * Extract source route from file path (e.g., /data/SCANNER1/incoming/... -> SCANNER1).
     */
    private String extractSourceRoute(Path filePath, String baseDir) {
        String relative = filePath.toString().substring(baseDir.length());
        if (relative.startsWith(File.separator)) {
            relative = relative.substring(1);
        }
        int sep = relative.indexOf(File.separator);
        if (sep > 0) {
            return relative.substring(0, sep);
        }
        return "UNKNOWN";
    }

    /**
     * Update study aggregates (series count, instance count, modalities, etc.).
     */
    public void updateStudyAggregates() {
        String sql = "UPDATE dicom_studies SET " +
                     "series_count = (SELECT COUNT(*) FROM dicom_series WHERE study_uid = dicom_studies.study_uid), " +
                     "instance_count = (SELECT COUNT(*) FROM dicom_instances i " +
                     "  JOIN dicom_series s ON i.series_uid = s.series_uid WHERE s.study_uid = dicom_studies.study_uid), " +
                     "total_size = (SELECT COALESCE(SUM(i.file_size), 0) FROM dicom_instances i " +
                     "  JOIN dicom_series s ON i.series_uid = s.series_uid WHERE s.study_uid = dicom_studies.study_uid), " +
                     "modalities = (SELECT GROUP_CONCAT(DISTINCT modality) FROM dicom_series WHERE study_uid = dicom_studies.study_uid)";

        try (java.sql.Statement stmt = store.getConnection().createStatement()) {
            stmt.executeUpdate(sql);
            log.debug("Updated study aggregates");
        } catch (java.sql.SQLException e) {
            log.error("Failed to update study aggregates: {}", e.getMessage(), e);
        }

        // Also update series instance counts
        String seriesSql = "UPDATE dicom_series SET " +
                          "instance_count = (SELECT COUNT(*) FROM dicom_instances WHERE series_uid = dicom_series.series_uid)";

        try (java.sql.Statement stmt = store.getConnection().createStatement()) {
            stmt.executeUpdate(seriesSql);
        } catch (java.sql.SQLException e) {
            log.error("Failed to update series aggregates: {}", e.getMessage(), e);
        }
    }

    /**
     * Get the current reindex job status.
     */
    public ReindexJob getCurrentJob() {
        if (currentJob != null) {
            return store.getReindexJob(currentJob.id);
        }
        return store.getLatestReindexJob();
    }

    /**
     * Cancel the currently running index job.
     * @return true if a job was running and cancel was requested
     */
    public boolean cancelJob() {
        if (currentJob != null && "running".equals(currentJob.status)) {
            log.info("Cancel requested for job {}", currentJob.id);
            cancelRequested = true;
            return true;
        }
        return false;
    }

    /**
     * Check if cancellation was requested.
     */
    public boolean isCancelRequested() {
        return cancelRequested;
    }

    /**
     * Reset cancellation flag (called when starting a new job).
     */
    private void resetCancelFlag() {
        cancelRequested = false;
    }

    /**
     * Parse a DICOM tag specification (e.g., "0008,0060" or "Modality").
     */
    public static int parseTag(String tagSpec) {
        if (tagSpec == null || tagSpec.isEmpty()) {
            return -1;
        }

        // Try hex format: "0008,0060" or "(0008,0060)"
        String cleaned = tagSpec.replace("(", "").replace(")", "").trim();
        if (cleaned.contains(",")) {
            try {
                String[] parts = cleaned.split(",");
                return Integer.parseInt(parts[0].trim(), 16) << 16 |
                       Integer.parseInt(parts[1].trim(), 16);
            } catch (Exception e) {
                return -1;
            }
        }

        // Try keyword lookup
        String lower = tagSpec.toLowerCase().replace("_", "").replace("-", "");
        Integer tag = TAG_NAME_MAP.get(lower);
        if (tag != null) {
            return tag;
        }

        return -1;
    }

    /**
     * Get a tag value as a string, formatted based on field type.
     */
    private String getTagValue(Attributes attrs, int tag, String fieldType) {
        if (!attrs.contains(tag)) {
            return null;
        }

        switch (fieldType.toLowerCase()) {
            case "number":
                // Try to get numeric value
                double d = attrs.getDouble(tag, Double.NaN);
                if (!Double.isNaN(d)) {
                    if (d == Math.floor(d)) {
                        return String.valueOf((long) d);
                    }
                    return String.valueOf(d);
                }
                return attrs.getString(tag);

            case "date":
                // Return as-is (YYYYMMDD format)
                return attrs.getString(tag);

            case "string":
            default:
                return attrs.getString(tag);
        }
    }

    /**
     * Check if a file appears to be a DICOM file.
     */
    private boolean isDicomFile(File file) {
        // Check extension
        String name = file.getName().toLowerCase();
        if (name.endsWith(".dcm") || name.endsWith(".dicom")) {
            return true;
        }

        // Check DICOM magic bytes
        if (file.length() > 132) {
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
                raf.seek(128);
                byte[] magic = new byte[4];
                raf.readFully(magic);
                return magic[0] == 'D' && magic[1] == 'I' && magic[2] == 'C' && magic[3] == 'M';
            } catch (IOException e) {
                // Not a DICOM file
            }
        }
        return false;
    }

    /**
     * Compute MD5 hash of a file for deduplication.
     */
    private String computeFileHash(File file) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[8192];
            try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                int read;
                while ((read = fis.read(buffer)) != -1) {
                    md.update(buffer, 0, read);
                }
            }
            byte[] hash = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("Failed to compute hash for {}: {}", file, e.getMessage());
            return null;
        }
    }

    /**
     * Get a list of common DICOM tags that can be indexed.
     */
    public static List<TagInfo> getAvailableTags() {
        List<TagInfo> tags = new ArrayList<>();

        // Patient
        tags.add(new TagInfo("PatientID", "0010,0020", "Patient ID", "study", "string"));
        tags.add(new TagInfo("PatientName", "0010,0010", "Patient Name", "study", "string"));
        tags.add(new TagInfo("PatientBirthDate", "0010,0030", "Patient Birth Date", "study", "date"));
        tags.add(new TagInfo("PatientSex", "0010,0040", "Patient Sex", "study", "string"));
        tags.add(new TagInfo("PatientAge", "0010,1010", "Patient Age", "study", "string"));

        // Study
        tags.add(new TagInfo("AccessionNumber", "0008,0050", "Accession Number", "study", "string"));
        tags.add(new TagInfo("StudyDescription", "0008,1030", "Study Description", "study", "string"));
        tags.add(new TagInfo("InstitutionName", "0008,0080", "Institution Name", "study", "string"));
        tags.add(new TagInfo("ReferringPhysicianName", "0008,0090", "Referring Physician", "study", "string"));
        tags.add(new TagInfo("PerformingPhysicianName", "0008,1050", "Performing Physician", "study", "string"));

        // Series
        tags.add(new TagInfo("Modality", "0008,0060", "Modality", "series", "string"));
        tags.add(new TagInfo("SeriesDescription", "0008,103E", "Series Description", "series", "string"));
        tags.add(new TagInfo("BodyPartExamined", "0018,0015", "Body Part", "series", "string"));
        tags.add(new TagInfo("ProtocolName", "0018,1030", "Protocol Name", "series", "string"));
        tags.add(new TagInfo("Laterality", "0020,0060", "Laterality", "series", "string"));

        // Equipment
        tags.add(new TagInfo("Manufacturer", "0008,0070", "Manufacturer", "study", "string"));
        tags.add(new TagInfo("ManufacturerModelName", "0008,1090", "Model Name", "study", "string"));
        tags.add(new TagInfo("StationName", "0008,1010", "Station Name", "study", "string"));
        tags.add(new TagInfo("SoftwareVersions", "0018,1020", "Software Version", "study", "string"));
        tags.add(new TagInfo("DeviceSerialNumber", "0018,1000", "Device Serial", "study", "string"));

        // Image
        tags.add(new TagInfo("Rows", "0028,0010", "Image Rows", "instance", "number"));
        tags.add(new TagInfo("Columns", "0028,0011", "Image Columns", "instance", "number"));
        tags.add(new TagInfo("SliceThickness", "0018,0050", "Slice Thickness", "instance", "number"));
        tags.add(new TagInfo("PixelSpacing", "0028,0030", "Pixel Spacing", "instance", "string"));

        // CT specific
        tags.add(new TagInfo("KVP", "0018,0060", "KVP", "instance", "number"));
        tags.add(new TagInfo("ExposureTime", "0018,1150", "Exposure Time", "instance", "number"));
        tags.add(new TagInfo("XRayTubeCurrent", "0018,1151", "Tube Current", "instance", "number"));
        tags.add(new TagInfo("ConvolutionKernel", "0018,1210", "Convolution Kernel", "instance", "string"));

        // MR specific
        tags.add(new TagInfo("MagneticFieldStrength", "0018,0087", "Field Strength", "instance", "number"));
        tags.add(new TagInfo("RepetitionTime", "0018,0080", "TR", "instance", "number"));
        tags.add(new TagInfo("EchoTime", "0018,0081", "TE", "instance", "number"));
        tags.add(new TagInfo("FlipAngle", "0018,1314", "Flip Angle", "instance", "number"));
        tags.add(new TagInfo("SequenceName", "0018,0024", "Sequence Name", "instance", "string"));

        return tags;
    }

    /**
     * Information about a DICOM tag.
     */
    public static class TagInfo {
        public String keyword;
        public String tag;
        public String displayName;
        public String level;
        public String fieldType;

        public TagInfo(String keyword, String tag, String displayName, String level, String fieldType) {
            this.keyword = keyword;
            this.tag = tag;
            this.displayName = displayName;
            this.level = level;
            this.fieldType = fieldType;
        }
    }

    /**
     * Shutdown the indexer.
     */
    public void shutdown() {
        executor.shutdown();
        try {
            executor.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
