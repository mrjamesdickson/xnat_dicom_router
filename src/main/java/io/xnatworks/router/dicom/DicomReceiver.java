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
    private final long studyTimeoutMs;

    private Device device;
    private ApplicationEntity ae;
    private Connection conn;
    private ExecutorService executor;
    private ScheduledExecutorService scheduledExecutor;
    private FolderWatcher folderWatcher;

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
     *   ├── incoming/                    # Raw received DICOM
     *   │   └── {StudyInstanceUID}/      # Study folder
     *   │       └── {SeriesInstanceUID}/ # Series folder
     *   │           └── {SOPInstanceUID}.dcm
     *   ├── processing/        # Currently being processed
     *   ├── completed/         # Successfully forwarded
     *   ├── failed/            # Failed to forward
     *   └── logs/              # AE-specific logs
     *
     * @param aeTitle the AE title for this receiver
     * @param port the port to listen on
     * @param baseDir the base directory for storing files
     * @param studyTimeoutSeconds seconds to wait after last file before considering study complete
     * @param onStudyComplete callback when study is complete
     */
    public DicomReceiver(String aeTitle, int port, String baseDir, int studyTimeoutSeconds, Consumer<ReceivedStudy> onStudyComplete) {
        this.aeTitle = aeTitle;
        this.port = port;
        this.baseDir = Paths.get(baseDir, aeTitle);
        this.incomingDir = this.baseDir.resolve("incoming");
        this.processingDir = this.baseDir.resolve("processing");
        this.completedDir = this.baseDir.resolve("completed");
        this.failedDir = this.baseDir.resolve("failed");
        this.logsDir = this.baseDir.resolve("logs");
        this.studyTimeoutMs = studyTimeoutSeconds * 1000L;
        this.onStudyComplete = onStudyComplete;
    }

    /**
     * Create receiver from route configuration.
     * Uses studyTimeoutSeconds from route config (default 30 seconds).
     */
    public DicomReceiver(AppConfig.RouteConfig route, String baseDir, Consumer<ReceivedStudy> onStudyComplete) {
        this(route.getAeTitle(), route.getPort(), baseDir, route.getStudyTimeoutSeconds(), onStudyComplete);
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

        // Start the folder watcher to detect study completion
        // This handles both files received via DICOM and files copied to the folder
        int studyTimeoutSeconds = (int) (studyTimeoutMs / 1000);
        folderWatcher = new FolderWatcher(incomingDir, aeTitle, studyTimeoutSeconds, this::handleStudyComplete);
        try {
            folderWatcher.start();
        } catch (IOException e) {
            log.error("[{}] Failed to start folder watcher: {}", aeTitle, e.getMessage(), e);
            logEvent("WARNING", "Folder watcher failed to start: " + e.getMessage());
        }
    }

    /**
     * Handle study completion from the FolderWatcher.
     * Updates statistics and forwards to the callback.
     */
    private void handleStudyComplete(ReceivedStudy study) {
        totalStudiesReceived++;

        log.info("[{}] Study complete: {} ({} files, {} bytes) from {}",
                aeTitle, study.getStudyUid(), study.getFileCount(),
                formatBytes(study.getTotalSize()), study.getCallingAeTitle());

        logEvent("STUDY_COMPLETE", String.format(
                "Study %s complete: %d files, %s from %s",
                study.getStudyUid(), study.getFileCount(),
                formatBytes(study.getTotalSize()), study.getCallingAeTitle()));

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

        // Stop folder watcher first
        if (folderWatcher != null) {
            folderWatcher.stop();
        }

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
                if (seriesUid == null) {
                    seriesUid = "UNKNOWN_SERIES";
                }

                // Store the file in incoming/{StudyUID}/{SeriesUID}/{SOPUID}.dcm
                Path studyDir = incomingDir.resolve(studyUid);
                Path seriesDir = studyDir.resolve(seriesUid);
                Files.createDirectories(seriesDir);

                // Use SOP Instance UID as filename
                String filename = sopInstanceUID + ".dcm";
                Path outputFile = seriesDir.resolve(filename);

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

                // Note: Study completion tracking is handled by FolderWatcher
                // which monitors the incoming directory for activity

                rsp.setInt(Tag.Status, VR.US, Status.Success);
            }
        };
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
        // Accept ALL known transfer syntaxes - use string UIDs for maximum compatibility
        String[] TRANSFER_SYNTAXES = {
                // Uncompressed
                "1.2.840.10008.1.2",       // Implicit VR Little Endian
                "1.2.840.10008.1.2.1",     // Explicit VR Little Endian
                "1.2.840.10008.1.2.2",     // Explicit VR Big Endian
                "1.2.840.10008.1.2.1.99",  // Deflated Explicit VR Little Endian
                // JPEG Baseline (Lossy)
                "1.2.840.10008.1.2.4.50",  // JPEG Baseline (Process 1)
                "1.2.840.10008.1.2.4.51",  // JPEG Extended (Process 2 & 4)
                "1.2.840.10008.1.2.4.52",  // JPEG Extended (Process 3 & 5) - Retired
                "1.2.840.10008.1.2.4.53",  // JPEG Spectral Selection, Non-Hierarchical (Process 6 & 8) - Retired
                "1.2.840.10008.1.2.4.54",  // JPEG Spectral Selection, Non-Hierarchical (Process 7 & 9) - Retired
                "1.2.840.10008.1.2.4.55",  // JPEG Full Progression, Non-Hierarchical (Process 10 & 12) - Retired
                "1.2.840.10008.1.2.4.56",  // JPEG Full Progression, Non-Hierarchical (Process 11 & 13) - Retired
                // JPEG Lossless (commonly used for mammography)
                "1.2.840.10008.1.2.4.57",  // JPEG Lossless, Non-Hierarchical (Process 14)
                "1.2.840.10008.1.2.4.58",  // JPEG Lossless, Non-Hierarchical (Process 15) - Retired
                "1.2.840.10008.1.2.4.59",  // JPEG Extended, Hierarchical (Process 16 & 18) - Retired
                "1.2.840.10008.1.2.4.60",  // JPEG Extended, Hierarchical (Process 17 & 19) - Retired
                "1.2.840.10008.1.2.4.61",  // JPEG Spectral Selection, Hierarchical (Process 20 & 22) - Retired
                "1.2.840.10008.1.2.4.62",  // JPEG Spectral Selection, Hierarchical (Process 21 & 23) - Retired
                "1.2.840.10008.1.2.4.63",  // JPEG Full Progression, Hierarchical (Process 24 & 26) - Retired
                "1.2.840.10008.1.2.4.64",  // JPEG Full Progression, Hierarchical (Process 25 & 27) - Retired
                "1.2.840.10008.1.2.4.65",  // JPEG Lossless, Hierarchical (Process 28) - Retired
                "1.2.840.10008.1.2.4.66",  // JPEG Lossless, Hierarchical (Process 29) - Retired
                "1.2.840.10008.1.2.4.70",  // JPEG Lossless, Non-Hierarchical, First-Order Prediction (Process 14, Selection Value 1)
                // JPEG-LS
                "1.2.840.10008.1.2.4.80",  // JPEG-LS Lossless
                "1.2.840.10008.1.2.4.81",  // JPEG-LS Lossy (Near-Lossless)
                // JPEG 2000 (commonly used for breast tomo and mammography)
                "1.2.840.10008.1.2.4.90",  // JPEG 2000 Image Compression (Lossless Only)
                "1.2.840.10008.1.2.4.91",  // JPEG 2000 Image Compression
                "1.2.840.10008.1.2.4.92",  // JPEG 2000 Part 2 Multi-component Image Compression (Lossless Only)
                "1.2.840.10008.1.2.4.93",  // JPEG 2000 Part 2 Multi-component Image Compression
                // High-Throughput JPEG 2000 (HTJ2K)
                "1.2.840.10008.1.2.4.201", // HTJ2K Lossless
                "1.2.840.10008.1.2.4.202", // HTJ2K Lossless RPCL
                "1.2.840.10008.1.2.4.203", // HTJ2K
                // JPIP
                "1.2.840.10008.1.2.4.94",  // JPIP Referenced
                "1.2.840.10008.1.2.4.95",  // JPIP Referenced Deflate
                // RLE
                "1.2.840.10008.1.2.5",     // RLE Lossless
                // MPEG2
                "1.2.840.10008.1.2.4.100", // MPEG2 Main Profile @ Main Level
                "1.2.840.10008.1.2.4.101", // MPEG2 Main Profile @ High Level
                "1.2.840.10008.1.2.4.102", // MPEG-4 AVC/H.264 High Profile / Level 4.1
                "1.2.840.10008.1.2.4.103", // MPEG-4 AVC/H.264 BD-compatible High Profile / Level 4.1
                "1.2.840.10008.1.2.4.104", // MPEG-4 AVC/H.264 High Profile / Level 4.2 For 2D Video
                "1.2.840.10008.1.2.4.105", // MPEG-4 AVC/H.264 High Profile / Level 4.2 For 3D Video
                "1.2.840.10008.1.2.4.106", // MPEG-4 AVC/H.264 Stereo High Profile / Level 4.2
                // HEVC/H.265
                "1.2.840.10008.1.2.4.107", // HEVC/H.265 Main Profile / Level 5.1
                "1.2.840.10008.1.2.4.108", // HEVC/H.265 Main 10 Profile / Level 5.1
                // SMPTE ST 2110
                "1.2.840.10008.1.2.7.1",   // SMPTE ST 2110-20 Uncompressed Progressive Active Video
                "1.2.840.10008.1.2.7.2",   // SMPTE ST 2110-20 Uncompressed Interlaced Active Video
                "1.2.840.10008.1.2.7.3"    // SMPTE ST 2110-30 PCM Digital Audio
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
         * Get list of DICOM files in this study (recursively from series subdirectories).
         */
        public List<File> getFiles() {
            if (path == null || !Files.exists(path)) {
                return Collections.emptyList();
            }
            try (Stream<Path> stream = Files.walk(path)) {
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
