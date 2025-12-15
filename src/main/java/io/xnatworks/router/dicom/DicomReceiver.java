/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.dicom;

import io.xnatworks.router.config.AppConfig;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomOutputStream;
import org.dcm4che3.net.*;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.service.BasicCEchoSCP;
import org.dcm4che3.net.service.BasicCStoreSCP;
import org.dcm4che3.net.service.DicomServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * DICOM C-STORE SCP receiver using dcm4che.
 * Receives DICOM files and stores them in AE-specific directories organized by Study UID.
 */
public class DicomReceiver implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DicomReceiver.class);

    private final String aeTitle;
    private final int port;
    private final Path baseDir;
    private final Path incomingDir;
    private final Path processingDir;
    private final Path completedDir;
    private final Path failedDir;
    private final Path logsDir;
    private final Consumer<ReceivedStudy> onStudyComplete;

    private Device device;
    private ApplicationEntity ae;
    private Connection conn;
    private ExecutorService executor;
    private ScheduledExecutorService scheduledExecutor;

    // Track current study for batching
    private String currentStudyUid;
    private long lastFileReceived;
    private static final long STUDY_TIMEOUT_MS = 30000; // 30 seconds of inactivity = study complete

    // Statistics
    private long totalFilesReceived = 0;
    private long totalBytesReceived = 0;
    private long totalStudiesReceived = 0;
    private LocalDateTime startTime;

    /**
     * Create receiver with AE-specific directory structure.
     *
     * Directory structure:
     * {baseDir}/{aeTitle}/
     *   ├── incoming/          # Raw received DICOM
     *   │   └── study_{uid}/   # Files grouped by study
     *   ├── processing/        # Currently being processed
     *   ├── completed/         # Successfully forwarded
     *   ├── failed/            # Failed to forward
     *   └── logs/              # AE-specific logs
     */
    public DicomReceiver(String aeTitle, int port, String baseDir, Consumer<ReceivedStudy> onStudyComplete) {
        this.aeTitle = aeTitle;
        this.port = port;
        this.baseDir = Paths.get(baseDir, aeTitle);
        this.incomingDir = this.baseDir.resolve("incoming");
        this.processingDir = this.baseDir.resolve("processing");
        this.completedDir = this.baseDir.resolve("completed");
        this.failedDir = this.baseDir.resolve("failed");
        this.logsDir = this.baseDir.resolve("logs");
        this.onStudyComplete = onStudyComplete;
    }

    /**
     * Create receiver from route configuration.
     */
    public DicomReceiver(AppConfig.RouteConfig route, String baseDir, Consumer<ReceivedStudy> onStudyComplete) {
        this(route.getAeTitle(), route.getPort(), baseDir, onStudyComplete);
    }

    /**
     * Start the DICOM receiver.
     */
    public void start() throws IOException, GeneralSecurityException {
        log.info("Starting DICOM receiver: AE={} Port={}", aeTitle, port);

        // Create all directories
        Files.createDirectories(incomingDir);
        Files.createDirectories(processingDir);
        Files.createDirectories(completedDir);
        Files.createDirectories(failedDir);
        Files.createDirectories(logsDir);

        // Log startup
        logEvent("STARTUP", "Receiver starting on port " + port);

        // Set up executor services
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "dicom-receiver-" + aeTitle);
            t.setDaemon(true);
            return t;
        });
        scheduledExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dicom-receiver-scheduler-" + aeTitle);
            t.setDaemon(true);
            return t;
        });

        // Create device
        device = new Device("dicom-router-" + aeTitle);
        device.setExecutor(executor);
        device.setScheduledExecutor(scheduledExecutor);

        // Create connection
        conn = new Connection();
        conn.setPort(port);
        conn.setReceivePDULength(Connection.DEF_MAX_PDU_LENGTH);
        conn.setSendPDULength(Connection.DEF_MAX_PDU_LENGTH);
        device.addConnection(conn);

        // Create application entity
        ae = new ApplicationEntity(aeTitle);
        ae.setAssociationAcceptor(true);
        ae.addConnection(conn);
        device.addApplicationEntity(ae);

        // Register services
        DicomServiceRegistry serviceRegistry = new DicomServiceRegistry();
        serviceRegistry.addDicomService(new BasicCEchoSCP());
        serviceRegistry.addDicomService(createStoreSCP());
        ae.setDimseRQHandler(serviceRegistry);

        // Accept all SOP classes
        addTransferCapabilities();

        // Bind and start
        device.bindConnections();

        startTime = LocalDateTime.now();
        log.info("DICOM receiver started: {} on port {}", aeTitle, port);
        logEvent("STARTED", "Receiver successfully started");

        // Scan for existing studies in incoming directory and process them
        scanAndProcessExistingStudies();
    }

    /**
     * Scan the incoming directory for existing studies and process them.
     * This handles studies that were received but not processed (e.g., after a restart).
     */
    private void scanAndProcessExistingStudies() {
        if (!Files.exists(incomingDir)) {
            return;
        }

        try (Stream<Path> studyDirs = Files.list(incomingDir)) {
            List<Path> existingStudies = studyDirs
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("study_"))
                    .collect(Collectors.toList());

            if (existingStudies.isEmpty()) {
                log.debug("[{}] No existing studies found in incoming directory", aeTitle);
                return;
            }

            log.info("[{}] Found {} existing studies in incoming directory - processing...",
                    aeTitle, existingStudies.size());
            logEvent("STARTUP_SCAN", "Found " + existingStudies.size() + " existing studies to process");

            for (Path studyPath : existingStudies) {
                try {
                    processExistingStudy(studyPath);
                } catch (Exception e) {
                    log.error("[{}] Error processing existing study {}: {}",
                            aeTitle, studyPath.getFileName(), e.getMessage(), e);
                    logEvent("ERROR", "Failed to process existing study " + studyPath.getFileName() + ": " + e.getMessage());
                }
            }

            log.info("[{}] Finished processing existing studies", aeTitle);
        } catch (IOException e) {
            log.error("[{}] Error scanning incoming directory: {}", aeTitle, e.getMessage(), e);
        }
    }

    /**
     * Process an existing study directory found during startup scan.
     */
    private void processExistingStudy(Path studyPath) throws IOException {
        String studyDirName = studyPath.getFileName().toString();
        String studyUid = studyDirName.startsWith("study_") ? studyDirName.substring(6) : studyDirName;

        // Count files and calculate size
        long fileCount;
        long totalSize;
        try (Stream<Path> files = Files.list(studyPath)) {
            List<Path> fileList = files.filter(Files::isRegularFile).collect(Collectors.toList());
            fileCount = fileList.size();
            totalSize = fileList.stream().mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        }

        if (fileCount == 0) {
            log.debug("[{}] Skipping empty study directory: {}", aeTitle, studyDirName);
            return;
        }

        log.info("[{}] Processing existing study: {} ({} files, {} bytes)",
                aeTitle, studyUid, fileCount, totalSize);

        // Create ReceivedStudy and trigger callback
        ReceivedStudy study = new ReceivedStudy();
        study.setStudyUid(studyUid);
        study.setPath(studyPath);
        study.setFileCount(fileCount);
        study.setTotalSize(totalSize);
        study.setAeTitle(aeTitle);
        study.setCallingAeTitle("STARTUP_SCAN");
        study.setReceivedAt(LocalDateTime.now());

        totalStudiesReceived++;

        if (onStudyComplete != null) {
            onStudyComplete.accept(study);
        }
    }

    /**
     * Stop the DICOM receiver.
     */
    public void stop() {
        log.info("Stopping DICOM receiver: {}", aeTitle);
        logEvent("SHUTDOWN", "Receiver stopping");

        if (device != null) {
            device.unbindConnections();
        }

        if (executor != null) {
            executor.shutdown();
            try {
                executor.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (scheduledExecutor != null) {
            scheduledExecutor.shutdown();
        }

        log.info("DICOM receiver stopped: {}", aeTitle);
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * Create C-STORE SCP handler.
     */
    private BasicCStoreSCP createStoreSCP() {
        return new BasicCStoreSCP("*") {
            @Override
            protected void store(Association as, PresentationContext pc, Attributes rq,
                                 PDVInputStream data, Attributes rsp) throws IOException {

                String callingAE = as.getCallingAET();
                String sopClassUID = rq.getString(Tag.AffectedSOPClassUID);
                String sopInstanceUID = rq.getString(Tag.AffectedSOPInstanceUID);
                String transferSyntax = pc.getTransferSyntax();

                log.debug("[{}] Receiving from {}: SOP Instance {}", aeTitle, callingAE, sopInstanceUID);

                // Read the DICOM data
                Attributes dataset = data.readDataset(transferSyntax);
                Attributes fmi = as.createFileMetaInformation(sopInstanceUID, sopClassUID, transferSyntax);

                // Get study info for organization
                String studyUid = dataset.getString(Tag.StudyInstanceUID);
                String seriesUid = dataset.getString(Tag.SeriesInstanceUID);
                String modality = dataset.getString(Tag.Modality, "OT");
                String patientId = dataset.getString(Tag.PatientID, "UNKNOWN");

                if (studyUid == null) {
                    studyUid = "UNKNOWN_STUDY";
                }

                // Store the file in AE-specific incoming directory
                Path studyDir = incomingDir.resolve("study_" + studyUid);
                Files.createDirectories(studyDir);

                // Use SOP Instance UID as filename
                String filename = sopInstanceUID + ".dcm";
                Path outputFile = studyDir.resolve(filename);

                long fileSize;
                try (DicomOutputStream dos = new DicomOutputStream(outputFile.toFile())) {
                    dos.writeDataset(fmi, dataset);
                    fileSize = outputFile.toFile().length();
                }

                // Update statistics
                totalFilesReceived++;
                totalBytesReceived += fileSize;

                log.debug("[{}] Stored: {} ({} bytes)", aeTitle, outputFile.getFileName(), fileSize);

                // Log receive event
                logReceive(callingAE, studyUid, seriesUid, sopInstanceUID, modality, patientId, fileSize);

                // Track study completion
                trackStudyActivity(studyUid, studyDir, callingAE);

                rsp.setInt(Tag.Status, VR.US, Status.Success);
            }
        };
    }

    /**
     * Track study activity for detecting study completion.
     */
    private synchronized void trackStudyActivity(String studyUid, Path studyDir, String callingAE) {
        lastFileReceived = System.currentTimeMillis();

        if (currentStudyUid != null && !currentStudyUid.equals(studyUid)) {
            // Different study - previous study is complete
            notifyStudyComplete(currentStudyUid, callingAE);
        }

        currentStudyUid = studyUid;

        // Schedule check for study completion
        scheduledExecutor.schedule(() -> checkStudyComplete(studyUid, studyDir, callingAE),
                STUDY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Check if study is complete (no files received recently).
     */
    private synchronized void checkStudyComplete(String studyUid, Path studyDir, String callingAE) {
        if (studyUid.equals(currentStudyUid) &&
                System.currentTimeMillis() - lastFileReceived >= STUDY_TIMEOUT_MS) {
            notifyStudyComplete(studyUid, callingAE);
            currentStudyUid = null;
        }
    }

    /**
     * Notify that a study is complete.
     */
    private void notifyStudyComplete(String studyUid, String callingAE) {
        if (onStudyComplete != null) {
            try {
                Path studyPath = incomingDir.resolve("study_" + studyUid);
                long fileCount = Files.list(studyPath).filter(Files::isRegularFile).count();
                long totalSize = Files.walk(studyPath)
                        .filter(Files::isRegularFile)
                        .mapToLong(p -> p.toFile().length())
                        .sum();

                totalStudiesReceived++;

                ReceivedStudy study = new ReceivedStudy();
                study.setStudyUid(studyUid);
                study.setPath(studyPath);
                study.setFileCount(fileCount);
                study.setTotalSize(totalSize);
                study.setAeTitle(aeTitle);
                study.setCallingAeTitle(callingAE);
                study.setReceivedAt(LocalDateTime.now());

                log.info("[{}] Study complete: {} ({} files, {} bytes) from {}",
                        aeTitle, studyUid, fileCount, formatBytes(totalSize), callingAE);

                logEvent("STUDY_COMPLETE", String.format(
                        "Study %s complete: %d files, %s from %s",
                        studyUid, fileCount, formatBytes(totalSize), callingAE));

                onStudyComplete.accept(study);
            } catch (Exception e) {
                log.error("[{}] Error notifying study complete: {}", aeTitle, e.getMessage(), e);
                logEvent("ERROR", "Failed to process study " + studyUid + ": " + e.getMessage());
            }
        }
    }

    /**
     * Log a receive event to the AE-specific log.
     */
    private void logReceive(String callingAE, String studyUid, String seriesUid,
                            String sopInstanceUid, String modality, String patientId, long fileSize) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String logLine = String.format("%s,RECEIVE,%s,%s,%s,%s,%s,%s,%d%n",
                timestamp, callingAE, patientId, studyUid, seriesUid, sopInstanceUid, modality, fileSize);

        try {
            Path logFile = logsDir.resolve("receive_" + LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd")) + ".csv");

            // Write header if new file
            if (!Files.exists(logFile)) {
                Files.writeString(logFile, "timestamp,event,calling_ae,patient_id,study_uid,series_uid,sop_instance_uid,modality,file_size\n");
            }

            Files.writeString(logFile, logLine, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("[{}] Failed to write to receive log: {}", aeTitle, e.getMessage());
        }
    }

    /**
     * Log a general event to the AE-specific log.
     */
    private void logEvent(String event, String message) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String logLine = String.format("%s,%s,%s%n", timestamp, event, message);

        try {
            Path logFile = logsDir.resolve("events_" + LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd")) + ".log");
            Files.writeString(logFile, logLine,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("[{}] Failed to write to event log: {}", aeTitle, e.getMessage());
        }
    }

    /**
     * Add transfer capabilities for all storage SOP classes.
     */
    private void addTransferCapabilities() {
        String[] TRANSFER_SYNTAXES = {
                UID.ImplicitVRLittleEndian,
                UID.ExplicitVRLittleEndian,
                UID.ExplicitVRBigEndian,
                UID.DeflatedExplicitVRLittleEndian,
                UID.JPEGLosslessSV1,
                UID.JPEGLossless,
                UID.JPEG2000,
                UID.JPEG2000Lossless,
                UID.JPEGBaseline8Bit,
                UID.JPEGExtended12Bit,
                UID.RLELossless,
                UID.MPEG2MPML,
                UID.MPEG2MPHL,
                UID.MPEG4HP41,
                UID.MPEG4HP41BD
        };

        // Comprehensive list of ALL storage SOP classes
        String[] SOP_CLASSES = {
                // Verification
                UID.Verification,
                // CT
                UID.CTImageStorage,
                UID.EnhancedCTImageStorage,
                UID.LegacyConvertedEnhancedCTImageStorage,
                // MR
                UID.MRImageStorage,
                UID.EnhancedMRImageStorage,
                UID.MRSpectroscopyStorage,
                UID.EnhancedMRColorImageStorage,
                UID.LegacyConvertedEnhancedMRImageStorage,
                // PET
                UID.PositronEmissionTomographyImageStorage,
                UID.EnhancedPETImageStorage,
                UID.LegacyConvertedEnhancedPETImageStorage,
                // Nuclear Medicine
                UID.NuclearMedicineImageStorage,
                // Ultrasound
                UID.UltrasoundImageStorage,
                UID.UltrasoundMultiFrameImageStorage,
                UID.EnhancedUSVolumeStorage,
                // Secondary Capture
                UID.SecondaryCaptureImageStorage,
                UID.MultiFrameSingleBitSecondaryCaptureImageStorage,
                UID.MultiFrameGrayscaleByteSecondaryCaptureImageStorage,
                UID.MultiFrameGrayscaleWordSecondaryCaptureImageStorage,
                UID.MultiFrameTrueColorSecondaryCaptureImageStorage,
                // X-Ray
                UID.DigitalXRayImageStorageForPresentation,
                UID.DigitalXRayImageStorageForProcessing,
                UID.DigitalMammographyXRayImageStorageForPresentation,
                UID.DigitalMammographyXRayImageStorageForProcessing,
                UID.DigitalIntraOralXRayImageStorageForPresentation,
                UID.DigitalIntraOralXRayImageStorageForProcessing,
                UID.XRayAngiographicImageStorage,
                UID.EnhancedXAImageStorage,
                UID.XRayRadiofluoroscopicImageStorage,
                UID.EnhancedXRFImageStorage,
                UID.XRay3DAngiographicImageStorage,
                UID.XRay3DCraniofacialImageStorage,
                UID.BreastProjectionXRayImageStorageForPresentation,
                UID.BreastProjectionXRayImageStorageForProcessing,
                // Computed Radiography
                UID.ComputedRadiographyImageStorage,
                // Mammography
                UID.BreastTomosynthesisImageStorage,
                // RT
                UID.RTImageStorage,
                UID.RTDoseStorage,
                UID.RTStructureSetStorage,
                UID.RTPlanStorage,
                UID.RTBeamsTreatmentRecordStorage,
                UID.RTBrachyTreatmentRecordStorage,
                UID.RTTreatmentSummaryRecordStorage,
                UID.RTIonPlanStorage,
                UID.RTIonBeamsTreatmentRecordStorage,
                // Encapsulated Documents
                UID.EncapsulatedPDFStorage,
                UID.EncapsulatedCDAStorage,
                UID.EncapsulatedSTLStorage,
                UID.EncapsulatedOBJStorage,
                UID.EncapsulatedMTLStorage,
                // SR
                UID.BasicTextSRStorage,
                UID.EnhancedSRStorage,
                UID.ComprehensiveSRStorage,
                UID.Comprehensive3DSRStorage,
                UID.ExtensibleSRStorage,
                UID.MammographyCADSRStorage,
                UID.ChestCADSRStorage,
                UID.XRayRadiationDoseSRStorage,
                UID.RadiopharmaceuticalRadiationDoseSRStorage,
                UID.ColonCADSRStorage,
                UID.ImplantationPlanSRStorage,
                UID.AcquisitionContextSRStorage,
                UID.SimplifiedAdultEchoSRStorage,
                UID.PatientRadiationDoseSRStorage,
                // Presentation States
                UID.GrayscaleSoftcopyPresentationStateStorage,
                UID.ColorSoftcopyPresentationStateStorage,
                UID.PseudoColorSoftcopyPresentationStateStorage,
                UID.BlendingSoftcopyPresentationStateStorage,
                UID.XAXRFGrayscaleSoftcopyPresentationStateStorage,
                UID.AdvancedBlendingPresentationStateStorage,
                // VL (Visible Light)
                UID.VLEndoscopicImageStorage,
                UID.VLMicroscopicImageStorage,
                UID.VLSlideCoordinatesMicroscopicImageStorage,
                UID.VLPhotographicImageStorage,
                UID.VideoEndoscopicImageStorage,
                UID.VideoMicroscopicImageStorage,
                UID.VideoPhotographicImageStorage,
                UID.VLWholeSlideMicroscopyImageStorage,
                UID.DermoscopicPhotographyImageStorage,
                // Ophthalmic
                UID.OphthalmicPhotography8BitImageStorage,
                UID.OphthalmicPhotography16BitImageStorage,
                UID.OphthalmicTomographyImageStorage,
                UID.WideFieldOphthalmicPhotographyStereographicProjectionImageStorage,
                UID.WideFieldOphthalmicPhotography3DCoordinatesImageStorage,
                UID.OphthalmicOpticalCoherenceTomographyEnFaceImageStorage,
                UID.OphthalmicOpticalCoherenceTomographyBscanVolumeAnalysisStorage,
                // Segmentation
                UID.SegmentationStorage,
                UID.SurfaceSegmentationStorage,
                // Mesh/Surface
                UID.SurfaceScanMeshStorage,
                UID.SurfaceScanPointCloudStorage,
                // Raw Data
                UID.RawDataStorage,
                // Parametric Map
                UID.ParametricMapStorage,
                // Waveform
                UID.TwelveLeadECGWaveformStorage,
                UID.GeneralECGWaveformStorage,
                UID.AmbulatoryECGWaveformStorage,
                // UID.General32BitECGWaveformStorage, // Not in dcm4che 5.25
                UID.HemodynamicWaveformStorage,
                UID.CardiacElectrophysiologyWaveformStorage,
                UID.BasicVoiceAudioWaveformStorage,
                UID.ArterialPulseWaveformStorage,
                UID.RespiratoryWaveformStorage,
                UID.MultichannelRespiratoryWaveformStorage,
                UID.RoutineScalpElectroencephalogramWaveformStorage,
                UID.ElectromyogramWaveformStorage,
                UID.ElectrooculogramWaveformStorage,
                UID.SleepElectroencephalogramWaveformStorage,
                UID.BodyPositionWaveformStorage,
                // Key Object Selection
                UID.KeyObjectSelectionDocumentStorage,
                // Hanging Protocol
                UID.HangingProtocolStorage,
                // Color Palette
                UID.ColorPaletteStorage,
                // Generic Implant Template
                UID.GenericImplantTemplateStorage,
                UID.ImplantAssemblyTemplateStorage,
                UID.ImplantTemplateGroupStorage,
                // Tractography
                UID.TractographyResultsStorage,
                // Content Assessment
                UID.ContentAssessmentResultsStorage,
                // Corneal Topography
                UID.CornealTopographyMapStorage,
                // Intravascular OCT
                UID.IntravascularOpticalCoherenceTomographyImageStorageForPresentation,
                UID.IntravascularOpticalCoherenceTomographyImageStorageForProcessing
        };

        for (String sopClass : SOP_CLASSES) {
            ae.addTransferCapability(new TransferCapability(null, sopClass,
                    TransferCapability.Role.SCP, TRANSFER_SYNTAXES));
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1048576) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1073741824) return String.format("%.1f MB", bytes / 1048576.0);
        return String.format("%.2f GB", bytes / 1073741824.0);
    }

    // Getters
    public String getAeTitle() { return aeTitle; }
    public int getPort() { return port; }
    public Path getBaseDir() { return baseDir; }
    public Path getIncomingDir() { return incomingDir; }
    public Path getProcessingDir() { return processingDir; }
    public Path getCompletedDir() { return completedDir; }
    public Path getFailedDir() { return failedDir; }
    public Path getLogsDir() { return logsDir; }

    public boolean isRunning() {
        return device != null && device.isInstalled();
    }

    public ReceiverStats getStats() {
        ReceiverStats stats = new ReceiverStats();
        stats.aeTitle = aeTitle;
        stats.port = port;
        stats.running = isRunning();
        stats.startTime = startTime;
        stats.totalFilesReceived = totalFilesReceived;
        stats.totalBytesReceived = totalBytesReceived;
        stats.totalStudiesReceived = totalStudiesReceived;
        return stats;
    }

    /**
     * Received study information.
     */
    public static class ReceivedStudy {
        private String studyUid;
        private Path path;
        private long fileCount;
        private long totalSize;
        private String aeTitle;
        private String callingAeTitle;
        private LocalDateTime receivedAt;

        public String getStudyUid() { return studyUid; }
        public void setStudyUid(String studyUid) { this.studyUid = studyUid; }

        public Path getPath() { return path; }
        public void setPath(Path path) { this.path = path; }

        /**
         * Get the study directory path (alias for getPath()).
         */
        public Path getStudyDir() { return path; }

        /**
         * Get list of DICOM files in this study.
         */
        public List<File> getFiles() {
            if (path == null || !Files.exists(path)) {
                return Collections.emptyList();
            }
            try (Stream<Path> stream = Files.list(path)) {
                return stream
                    .filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .collect(Collectors.toList());
            } catch (IOException e) {
                return Collections.emptyList();
            }
        }

        public int getFileCount() { return (int) fileCount; }
        public void setFileCount(long fileCount) { this.fileCount = fileCount; }

        public long getTotalSize() { return totalSize; }
        public void setTotalSize(long totalSize) { this.totalSize = totalSize; }

        public String getAeTitle() { return aeTitle; }
        public void setAeTitle(String aeTitle) { this.aeTitle = aeTitle; }

        public String getCallingAeTitle() { return callingAeTitle; }
        public void setCallingAeTitle(String callingAeTitle) { this.callingAeTitle = callingAeTitle; }

        public LocalDateTime getReceivedAt() { return receivedAt; }
        public void setReceivedAt(LocalDateTime receivedAt) { this.receivedAt = receivedAt; }
    }

    /**
     * Receiver statistics.
     */
    public static class ReceiverStats {
        public String aeTitle;
        public int port;
        public boolean running;
        public LocalDateTime startTime;
        public long totalFilesReceived;
        public long totalBytesReceived;
        public long totalStudiesReceived;

        public String getUptimeFormatted() {
            if (startTime == null) return "N/A";
            java.time.Duration duration = java.time.Duration.between(startTime, LocalDateTime.now());
            long hours = duration.toHours();
            long minutes = duration.toMinutesPart();
            long seconds = duration.toSecondsPart();
            return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        }
    }
}
