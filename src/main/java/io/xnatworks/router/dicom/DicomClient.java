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
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.net.*;
import org.dcm4che3.net.pdu.AAssociateRQ;
import org.dcm4che3.net.pdu.PresentationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * DICOM client for C-STORE SCU, C-FIND, C-MOVE, and C-GET operations.
 * Used for forwarding DICOM to other AE destinations and Query/Retrieve.
 */
public class DicomClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DicomClient.class);

    private final String destinationName;
    private final String calledAeTitle;
    private final String callingAeTitle;
    private final String host;
    private final int port;
    private final boolean useTls;

    private Device device;
    private ApplicationEntity ae;
    private Connection conn;
    private Connection remoteConn;
    private ExecutorService executor;
    private ScheduledExecutorService scheduledExecutor;

    // Common transfer syntaxes
    private static final String[] TRANSFER_SYNTAXES = {
            UID.ImplicitVRLittleEndian,
            UID.ExplicitVRLittleEndian,
            UID.ExplicitVRBigEndian
    };

    // Extended transfer syntaxes for image storage
    private static final String[] IMAGE_TRANSFER_SYNTAXES = {
            UID.ImplicitVRLittleEndian,
            UID.ExplicitVRLittleEndian,
            UID.ExplicitVRBigEndian,
            UID.JPEGLosslessSV1,
            UID.JPEGLossless,
            UID.JPEG2000,
            UID.JPEG2000Lossless,
            UID.JPEGBaseline8Bit,
            UID.JPEGExtended12Bit,
            UID.RLELossless
    };

    public DicomClient(String destinationName, AppConfig.DicomAeDestination config) {
        this.destinationName = destinationName;
        this.calledAeTitle = config.getAeTitle();
        this.callingAeTitle = config.getCallingAeTitle();
        this.host = config.getHost();
        this.port = config.getPort();
        this.useTls = config.isUseTls();
    }

    public DicomClient(String destinationName, String calledAeTitle, String host, int port,
                       String callingAeTitle, boolean useTls) {
        this.destinationName = destinationName;
        this.calledAeTitle = calledAeTitle;
        this.host = host;
        this.port = port;
        this.callingAeTitle = callingAeTitle;
        this.useTls = useTls;
    }

    /**
     * Initialize the DICOM device and connection.
     */
    private void initialize() throws IOException, GeneralSecurityException {
        if (device != null) return;

        executor = Executors.newCachedThreadPool();
        scheduledExecutor = Executors.newSingleThreadScheduledExecutor();

        device = new Device("dicom-router-client-" + destinationName);
        device.setExecutor(executor);
        device.setScheduledExecutor(scheduledExecutor);

        // Local connection
        conn = new Connection();
        device.addConnection(conn);

        // Remote connection
        remoteConn = new Connection();
        remoteConn.setHostname(host);
        remoteConn.setPort(port);

        // Application entity
        ae = new ApplicationEntity(callingAeTitle);
        ae.addConnection(conn);
        ae.setAssociationInitiator(true);
        device.addApplicationEntity(ae);

        log.debug("Initialized DICOM client for '{}': {} -> {}@{}:{}",
                destinationName, callingAeTitle, calledAeTitle, host, port);
    }

    /**
     * Test connectivity with C-ECHO.
     */
    public boolean echo() {
        try {
            initialize();

            AAssociateRQ rq = new AAssociateRQ();
            rq.setCalledAET(calledAeTitle);
            rq.setCallingAET(callingAeTitle);
            rq.addPresentationContext(new PresentationContext(1, UID.Verification, TRANSFER_SYNTAXES));

            Association as = ae.connect(remoteConn, rq);
            try {
                as.cecho().next();
                log.debug("C-ECHO successful to '{}'", destinationName);
                return true;
            } finally {
                as.release();
            }
        } catch (Exception e) {
            log.debug("C-ECHO failed to '{}': {}", destinationName, e.getMessage());
            return false;
        }
    }

    /**
     * Send DICOM files via C-STORE.
     */
    public StoreResult store(List<File> files) throws IOException, GeneralSecurityException, InterruptedException, IncompatibleConnectionException {
        initialize();

        StoreResult result = new StoreResult();
        result.setDestination(destinationName);
        result.setTotalFiles(files.size());

        long startTime = System.currentTimeMillis();

        // Collect all SOP classes needed
        List<FileInfo> fileInfos = new ArrayList<>();
        for (File file : files) {
            try (DicomInputStream dis = new DicomInputStream(file)) {
                Attributes fmi = dis.readFileMetaInformation();
                String sopClassUid = fmi.getString(Tag.MediaStorageSOPClassUID);
                String sopInstanceUid = fmi.getString(Tag.MediaStorageSOPInstanceUID);
                String transferSyntax = fmi.getString(Tag.TransferSyntaxUID);

                FileInfo info = new FileInfo();
                info.file = file;
                info.sopClassUid = sopClassUid;
                info.sopInstanceUid = sopInstanceUid;
                info.transferSyntax = transferSyntax;
                fileInfos.add(info);
            } catch (Exception e) {
                log.warn("Failed to read DICOM file {}: {}", file.getName(), e.getMessage());
                result.incrementFailed();
            }
        }

        if (fileInfos.isEmpty()) {
            result.setDurationMs(System.currentTimeMillis() - startTime);
            return result;
        }

        // Build association request with all needed presentation contexts
        AAssociateRQ rq = new AAssociateRQ();
        rq.setCalledAET(calledAeTitle);
        rq.setCallingAET(callingAeTitle);

        int pcid = 1;
        for (FileInfo info : fileInfos) {
            rq.addPresentationContext(new PresentationContext(pcid, info.sopClassUid, IMAGE_TRANSFER_SYNTAXES));
            pcid += 2;
            if (pcid > 255) break; // Max presentation contexts
        }

        Association as = null;
        try {
            as = ae.connect(remoteConn, rq);

            for (FileInfo info : fileInfos) {
                try {
                    byte[] data = Files.readAllBytes(info.file.toPath());

                    DimseRSPHandler rspHandler = new DimseRSPHandler(as.nextMessageID()) {
                        @Override
                        public void onDimseRSP(Association as, Attributes cmd, Attributes data) {
                            int status = cmd.getInt(Tag.Status, -1);
                            if (status == Status.Success) {
                                result.incrementSuccess();
                            } else {
                                result.incrementFailed();
                                log.warn("C-STORE failed for {} with status 0x{}", info.file.getName(),
                                        Integer.toHexString(status));
                            }
                        }
                    };

                    // Find accepted presentation context - use ImplicitVRLittleEndian as default
                    String ts = UID.ImplicitVRLittleEndian;

                    // Create store request
                    Attributes storeRq = new Attributes();
                    storeRq.setString(Tag.AffectedSOPClassUID, VR.UI, info.sopClassUid);
                    storeRq.setString(Tag.AffectedSOPInstanceUID, VR.UI, info.sopInstanceUid);
                    storeRq.setInt(Tag.Priority, VR.US, Priority.NORMAL);

                    // Read DICOM dataset
                    try (DicomInputStream dis = new DicomInputStream(info.file)) {
                        dis.readFileMetaInformation();
                        Attributes dataset = dis.readDataset();

                        as.cstore(info.sopClassUid, info.sopInstanceUid, Priority.NORMAL,
                                new DataWriterAdapter(dataset), ts, rspHandler);
                    }

                } catch (Exception e) {
                    log.error("Failed to store {}: {}", info.file.getName(), e.getMessage());
                    result.incrementFailed();
                }
            }

            // Wait for all responses
            as.waitForOutstandingRSP();

        } finally {
            if (as != null) {
                try {
                    as.release();
                } catch (Exception e) {
                    log.debug("Error releasing association: {}", e.getMessage());
                }
            }
        }

        result.setDurationMs(System.currentTimeMillis() - startTime);
        log.info("C-STORE to '{}': {} succeeded, {} failed in {}ms",
                destinationName, result.getSuccessCount(), result.getFailedCount(), result.getDurationMs());

        return result;
    }

    /**
     * Query with C-FIND at Study level.
     */
    public List<Attributes> findStudies(Attributes queryKeys) throws Exception {
        return find(UID.StudyRootQueryRetrieveInformationModelFind, queryKeys);
    }

    /**
     * Query with C-FIND at Series level.
     */
    public List<Attributes> findSeries(Attributes queryKeys) throws Exception {
        return find(UID.StudyRootQueryRetrieveInformationModelFind, queryKeys);
    }

    /**
     * Query with C-FIND.
     */
    public List<Attributes> find(String sopClassUid, Attributes queryKeys) throws Exception {
        initialize();

        List<Attributes> results = new ArrayList<>();

        AAssociateRQ rq = new AAssociateRQ();
        rq.setCalledAET(calledAeTitle);
        rq.setCallingAET(callingAeTitle);
        rq.addPresentationContext(new PresentationContext(1, sopClassUid, TRANSFER_SYNTAXES));

        Association as = null;
        try {
            as = ae.connect(remoteConn, rq);

            DimseRSPHandler rspHandler = new DimseRSPHandler(as.nextMessageID()) {
                @Override
                public void onDimseRSP(Association as, Attributes cmd, Attributes data) {
                    int status = cmd.getInt(Tag.Status, -1);
                    if (Status.isPending(status) && data != null) {
                        results.add(data);
                    }
                }
            };

            as.cfind(sopClassUid, Priority.NORMAL, queryKeys, null, rspHandler);
            as.waitForOutstandingRSP();

        } finally {
            if (as != null && as.isReadyForDataTransfer()) {
                try {
                    as.release();
                } catch (Exception e) {
                    log.debug("Error releasing association: {}", e.getMessage());
                }
            }
        }

        log.info("C-FIND to '{}': found {} results", destinationName, results.size());
        return results;
    }

    /**
     * Retrieve with C-MOVE.
     */
    public MoveResult move(String sopClassUid, Attributes queryKeys, String moveDestination) throws Exception {
        initialize();

        MoveResult result = new MoveResult();
        result.setDestination(destinationName);

        AAssociateRQ rq = new AAssociateRQ();
        rq.setCalledAET(calledAeTitle);
        rq.setCallingAET(callingAeTitle);
        rq.addPresentationContext(new PresentationContext(1, sopClassUid, TRANSFER_SYNTAXES));

        Association as = null;
        try {
            as = ae.connect(remoteConn, rq);
            long startTime = System.currentTimeMillis();

            DimseRSPHandler rspHandler = new DimseRSPHandler(as.nextMessageID()) {
                @Override
                public void onDimseRSP(Association as, Attributes cmd, Attributes data) {
                    int status = cmd.getInt(Tag.Status, -1);
                    result.setStatus(status);

                    if (cmd.containsValue(Tag.NumberOfCompletedSuboperations)) {
                        result.setCompleted(cmd.getInt(Tag.NumberOfCompletedSuboperations, 0));
                    }
                    if (cmd.containsValue(Tag.NumberOfFailedSuboperations)) {
                        result.setFailed(cmd.getInt(Tag.NumberOfFailedSuboperations, 0));
                    }
                    if (cmd.containsValue(Tag.NumberOfWarningSuboperations)) {
                        result.setWarnings(cmd.getInt(Tag.NumberOfWarningSuboperations, 0));
                    }
                    if (cmd.containsValue(Tag.NumberOfRemainingSuboperations)) {
                        result.setRemaining(cmd.getInt(Tag.NumberOfRemainingSuboperations, 0));
                    }
                }
            };

            as.cmove(sopClassUid, Priority.NORMAL, queryKeys, null, moveDestination, rspHandler);
            as.waitForOutstandingRSP();

            result.setDurationMs(System.currentTimeMillis() - startTime);

        } finally {
            if (as != null && as.isReadyForDataTransfer()) {
                try {
                    as.release();
                } catch (Exception e) {
                    log.debug("Error releasing association: {}", e.getMessage());
                }
            }
        }

        log.info("C-MOVE from '{}' to '{}': {} completed, {} failed in {}ms",
                destinationName, moveDestination, result.getCompleted(), result.getFailed(), result.getDurationMs());

        return result;
    }

    /**
     * Retrieve with C-GET (files stored locally).
     */
    public GetResult get(String sopClassUid, Attributes queryKeys, Path outputDir) throws Exception {
        initialize();

        GetResult result = new GetResult();
        result.setDestination(destinationName);
        result.setOutputDirectory(outputDir.toString());

        Files.createDirectories(outputDir);

        // TODO: Implement C-GET with local storage SCP
        // This requires setting up a temporary SCP to receive the files
        throw new UnsupportedOperationException("C-GET not yet implemented - use C-MOVE instead");
    }

    @Override
    public void close() {
        if (executor != null) {
            executor.shutdown();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
        if (scheduledExecutor != null) {
            scheduledExecutor.shutdown();
            scheduledExecutor = null;
        }
        // Reset all state so the client can be reinitialized
        device = null;
        ae = null;
        conn = null;
        remoteConn = null;
    }

    public String getDestinationName() { return destinationName; }
    public String getCalledAeTitle() { return calledAeTitle; }
    public String getHost() { return host; }
    public int getPort() { return port; }

    // Helper classes
    private static class FileInfo {
        File file;
        String sopClassUid;
        String sopInstanceUid;
        String transferSyntax;
    }

    /**
     * Result of C-STORE operation.
     */
    public static class StoreResult {
        private String destination;
        private int totalFiles;
        private int successCount;
        private int failedCount;
        private long durationMs;

        public String getDestination() { return destination; }
        public void setDestination(String destination) { this.destination = destination; }

        public int getTotalFiles() { return totalFiles; }
        public void setTotalFiles(int totalFiles) { this.totalFiles = totalFiles; }

        public int getSuccessCount() { return successCount; }
        public void incrementSuccess() { this.successCount++; }

        public int getFailedCount() { return failedCount; }
        public void incrementFailed() { this.failedCount++; }

        public long getDurationMs() { return durationMs; }
        public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

        public boolean isSuccess() { return failedCount == 0 && successCount > 0; }
    }

    /**
     * Result of C-MOVE operation.
     */
    public static class MoveResult {
        private String destination;
        private int status;
        private int completed;
        private int failed;
        private int warnings;
        private int remaining;
        private long durationMs;

        public String getDestination() { return destination; }
        public void setDestination(String destination) { this.destination = destination; }

        public int getStatus() { return status; }
        public void setStatus(int status) { this.status = status; }

        public int getCompleted() { return completed; }
        public void setCompleted(int completed) { this.completed = completed; }

        public int getFailed() { return failed; }
        public void setFailed(int failed) { this.failed = failed; }

        public int getWarnings() { return warnings; }
        public void setWarnings(int warnings) { this.warnings = warnings; }

        public int getRemaining() { return remaining; }
        public void setRemaining(int remaining) { this.remaining = remaining; }

        public long getDurationMs() { return durationMs; }
        public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

        public boolean isSuccess() { return status == Status.Success || status == Status.Pending; }
    }

    /**
     * Result of C-GET operation.
     */
    public static class GetResult {
        private String destination;
        private String outputDirectory;
        private int fileCount;
        private long totalBytes;
        private long durationMs;

        public String getDestination() { return destination; }
        public void setDestination(String destination) { this.destination = destination; }

        public String getOutputDirectory() { return outputDirectory; }
        public void setOutputDirectory(String outputDirectory) { this.outputDirectory = outputDirectory; }

        public int getFileCount() { return fileCount; }
        public void setFileCount(int fileCount) { this.fileCount = fileCount; }

        public long getTotalBytes() { return totalBytes; }
        public void setTotalBytes(long totalBytes) { this.totalBytes = totalBytes; }

        public long getDurationMs() { return durationMs; }
        public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    }
}
