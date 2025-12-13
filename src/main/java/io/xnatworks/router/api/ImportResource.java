/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.api;

import io.xnatworks.router.anon.ScriptLibrary;
import io.xnatworks.router.broker.HonestBrokerService;
import io.xnatworks.router.config.AppConfig;
import io.xnatworks.router.dicom.DicomClient;
import io.xnatworks.router.dicom.DicomReceiver;
import io.xnatworks.router.routing.DestinationManager;
import io.xnatworks.router.tracking.TransferTracker;
import io.xnatworks.router.xnat.XnatClient;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.io.DicomInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * REST API for importing DICOM files from disk.
 */
@Path("/import")
@Produces(MediaType.APPLICATION_JSON)
public class ImportResource {
    private static final Logger log = LoggerFactory.getLogger(ImportResource.class);

    private final AppConfig config;
    private final DestinationManager destinationManager;
    private final TransferTracker transferTracker;
    private final ScriptLibrary scriptLibrary;
    private final HonestBrokerService honestBrokerService;

    // Track running imports
    private static final Map<String, ImportJob> runningJobs = new ConcurrentHashMap<>();
    private static final ExecutorService executor = Executors.newFixedThreadPool(2);

    public ImportResource(AppConfig config, DestinationManager destinationManager,
                          TransferTracker transferTracker, ScriptLibrary scriptLibrary,
                          HonestBrokerService honestBrokerService) {
        this.config = config;
        this.destinationManager = destinationManager;
        this.transferTracker = transferTracker;
        this.scriptLibrary = scriptLibrary;
        this.honestBrokerService = honestBrokerService;
    }

    /**
     * Get available routes for import.
     */
    @GET
    @Path("/routes")
    public Response getAvailableRoutes() {
        List<Map<String, Object>> routes = new ArrayList<>();
        for (AppConfig.RouteConfig route : config.getRoutes()) {
            if (route.isEnabled()) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("aeTitle", route.getAeTitle());
                r.put("port", route.getPort());
                r.put("description", route.getDescription());
                r.put("destinationCount", route.getDestinations().size());
                routes.add(r);
            }
        }
        return Response.ok(routes).build();
    }

    /**
     * Scan a directory for DICOM files.
     */
    @POST
    @Path("/scan")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response scanDirectory(Map<String, Object> request) {
        String path = (String) request.get("path");
        Boolean recursive = (Boolean) request.getOrDefault("recursive", true);

        if (path == null || path.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Path is required"))
                    .build();
        }

        File dir = new File(path);
        if (!dir.exists()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Directory does not exist: " + path))
                    .build();
        }

        if (!dir.isDirectory()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Path is not a directory: " + path))
                    .build();
        }

        try {
            List<File> dicomFiles = scanForDicomFiles(dir, recursive);
            Map<String, List<File>> studiesByUid = groupByStudyUid(dicomFiles);

            List<Map<String, Object>> studies = new ArrayList<>();
            for (Map.Entry<String, List<File>> entry : studiesByUid.entrySet()) {
                Map<String, Object> study = new LinkedHashMap<>();
                study.put("studyUid", entry.getKey());
                study.put("fileCount", entry.getValue().size());
                study.put("totalSize", entry.getValue().stream().mapToLong(File::length).sum());

                // Extract additional info from first file
                if (!entry.getValue().isEmpty()) {
                    try (DicomInputStream dis = new DicomInputStream(entry.getValue().get(0))) {
                        Attributes attrs = dis.readDataset();
                        study.put("patientId", attrs.getString(Tag.PatientID, ""));
                        study.put("patientName", attrs.getString(Tag.PatientName, ""));
                        study.put("studyDate", attrs.getString(Tag.StudyDate, ""));
                        study.put("modality", attrs.getString(Tag.Modality, ""));
                        study.put("studyDescription", attrs.getString(Tag.StudyDescription, ""));
                    } catch (IOException e) {
                        log.debug("Could not read DICOM metadata: {}", e.getMessage());
                    }
                }

                studies.add(study);
            }

            return Response.ok(Map.of(
                    "path", path,
                    "totalFiles", dicomFiles.size(),
                    "studies", studies
            )).build();
        } catch (Exception e) {
            log.error("Error scanning directory: {}", e.getMessage(), e);
            return Response.serverError()
                    .entity(Map.of("error", "Failed to scan directory: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Start an import job.
     */
    @POST
    @Path("/start")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response startImport(Map<String, Object> request) {
        String path = (String) request.get("path");
        String routeAeTitle = (String) request.get("route");
        Boolean recursive = (Boolean) request.getOrDefault("recursive", true);
        Boolean moveFiles = (Boolean) request.getOrDefault("moveFiles", false);

        if (path == null || path.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Path is required"))
                    .build();
        }

        if (routeAeTitle == null || routeAeTitle.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Route is required"))
                    .build();
        }

        File dir = new File(path);
        if (!dir.exists() || !dir.isDirectory()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid directory: " + path))
                    .build();
        }

        AppConfig.RouteConfig route = config.findRouteByAeTitle(routeAeTitle);
        if (route == null || !route.isEnabled()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Route not found or disabled: " + routeAeTitle))
                    .build();
        }

        // Create job
        String jobId = UUID.randomUUID().toString().substring(0, 8);
        ImportJob job = new ImportJob(jobId, path, routeAeTitle, recursive, moveFiles);
        runningJobs.put(jobId, job);

        // Start async processing
        executor.submit(() -> runImportJob(job, route));

        return Response.accepted(Map.of(
                "jobId", jobId,
                "status", "started",
                "path", path,
                "route", routeAeTitle
        )).build();
    }

    /**
     * Get import job status.
     */
    @GET
    @Path("/jobs/{jobId}")
    public Response getJobStatus(@PathParam("jobId") String jobId) {
        ImportJob job = runningJobs.get(jobId);
        if (job == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Job not found: " + jobId))
                    .build();
        }

        return Response.ok(job.toMap()).build();
    }

    /**
     * List all import jobs.
     */
    @GET
    @Path("/jobs")
    public Response listJobs() {
        List<Map<String, Object>> jobs = runningJobs.values().stream()
                .map(ImportJob::toMap)
                .collect(Collectors.toList());
        return Response.ok(jobs).build();
    }

    /**
     * Cancel an import job.
     */
    @DELETE
    @Path("/jobs/{jobId}")
    public Response cancelJob(@PathParam("jobId") String jobId) {
        ImportJob job = runningJobs.get(jobId);
        if (job == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Job not found: " + jobId))
                    .build();
        }

        job.cancel();
        return Response.ok(Map.of("status", "cancelled", "jobId", jobId)).build();
    }

    // ========== Helper methods ==========

    private void runImportJob(ImportJob job, AppConfig.RouteConfig route) {
        try {
            job.setStatus("scanning");

            // Scan for DICOM files
            File dir = new File(job.getPath());
            List<File> dicomFiles = scanForDicomFiles(dir, job.isRecursive());
            Map<String, List<File>> studiesByUid = groupByStudyUid(dicomFiles);

            job.setTotalFiles(dicomFiles.size());
            job.setTotalStudies(studiesByUid.size());
            job.setStatus("processing");

            java.nio.file.Path baseDir = Paths.get(config.getReceiver().getBaseDir());
            Files.createDirectories(baseDir);

            for (Map.Entry<String, List<File>> entry : studiesByUid.entrySet()) {
                if (job.isCancelled()) {
                    job.setStatus("cancelled");
                    return;
                }

                String studyUid = entry.getKey();
                List<File> files = entry.getValue();

                job.setCurrentStudy(studyUid);

                try {
                    // Copy/move files to incoming directory
                    java.nio.file.Path incomingDir = baseDir.resolve(route.getAeTitle()).resolve("incoming").resolve(studyUid);
                    Files.createDirectories(incomingDir);

                    for (File srcFile : files) {
                        java.nio.file.Path destPath = incomingDir.resolve(srcFile.getName());
                        if (job.isMoveFiles()) {
                            Files.move(srcFile.toPath(), destPath, StandardCopyOption.REPLACE_EXISTING);
                        } else {
                            Files.copy(srcFile.toPath(), destPath, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }

                    // Create study object and process
                    DicomReceiver.ReceivedStudy study = new DicomReceiver.ReceivedStudy();
                    study.setStudyUid(studyUid);
                    study.setPath(incomingDir);
                    study.setAeTitle(route.getAeTitle());
                    study.setCallingAeTitle("LOCAL_IMPORT");
                    study.setFileCount(files.size());
                    long totalSize = files.stream().mapToLong(File::length).sum();
                    study.setTotalSize(totalSize);
                    study.setReceivedAt(java.time.LocalDateTime.now());

                    boolean success = processStudy(study, route);
                    job.incrementProcessedStudies();

                    if (success) {
                        job.incrementSuccessCount();
                    } else {
                        job.incrementFailCount();
                    }
                } catch (Exception e) {
                    log.error("Error processing study {}: {}", studyUid, e.getMessage(), e);
                    job.incrementFailCount();
                    job.addError(studyUid + ": " + e.getMessage());
                }
            }

            job.setStatus("completed");
            job.setCurrentStudy(null);

        } catch (Exception e) {
            log.error("Import job {} failed: {}", job.getId(), e.getMessage(), e);
            job.setStatus("failed");
            job.addError(e.getMessage());
        }
    }

    private boolean processStudy(DicomReceiver.ReceivedStudy study, AppConfig.RouteConfig route) {
        TransferTracker.TransferRecord transfer = transferTracker.createTransfer(
                route.getAeTitle(),
                study.getStudyUid(),
                study.getCallingAeTitle(),
                study.getFileCount(),
                study.getTotalSize()
        );
        String transferId = transfer.getId();
        transferTracker.startProcessing(transferId);

        boolean allSuccess = true;
        boolean anySuccess = false;

        for (AppConfig.RouteDestination routeDest : route.getDestinations()) {
            if (!routeDest.isEnabled()) {
                continue;
            }

            String destName = routeDest.getDestination();
            AppConfig.Destination dest = config.getDestination(destName);
            if (dest == null || !dest.isEnabled()) {
                continue;
            }

            try {
                long startTime = System.currentTimeMillis();
                boolean success = false;
                String message = null;
                int filesTransferred = 0;

                if (dest instanceof AppConfig.XnatDestination) {
                    XnatClient client = destinationManager.getXnatClient(destName);
                    if (client == null || !destinationManager.isAvailable(destName)) {
                        throw new RuntimeException("XNAT destination unavailable: " + destName);
                    }

                    File zipFile = createZipFromFiles(study.getFiles());
                    try {
                        String projectId = routeDest.getProjectId();
                        if (projectId == null || projectId.isEmpty()) {
                            projectId = "IMPORTED";
                        }

                        String subjectId;
                        if (routeDest.isUseHonestBroker() && routeDest.getHonestBrokerName() != null && honestBrokerService != null) {
                            String originalPatientId = extractPatientIdFromFiles(study.getFiles());
                            String deidentifiedId = honestBrokerService.lookup(routeDest.getHonestBrokerName(), originalPatientId);
                            subjectId = deidentifiedId != null ? deidentifiedId :
                                    routeDest.getSubjectPrefix() + "_" + study.getStudyUid().substring(Math.max(0, study.getStudyUid().length() - 8));
                        } else {
                            subjectId = generateSubjectIdFromFiles(study.getFiles(), routeDest.getSubjectPrefix());
                        }

                        String sessionLabel = routeDest.getSessionPrefix() + LocalDate.now().toString().replace("-", "") +
                                "_" + study.getStudyUid().substring(Math.max(0, study.getStudyUid().length() - 8));

                        XnatClient.UploadResult result = client.uploadWithRetry(
                                zipFile, projectId, subjectId, sessionLabel,
                                routeDest.isAutoArchive(),
                                routeDest.getRetryCount(),
                                routeDest.getRetryDelaySeconds() * 1000L
                        );

                        success = result.isSuccess();
                        filesTransferred = study.getFileCount();
                        message = success ? "Uploaded successfully" : result.getErrorMessage();
                    } finally {
                        if (zipFile.exists()) {
                            zipFile.delete();
                        }
                    }
                } else if (dest instanceof AppConfig.DicomAeDestination) {
                    DicomClient client = destinationManager.getDicomClient(destName);
                    if (client == null || !destinationManager.isAvailable(destName)) {
                        throw new RuntimeException("DICOM destination unavailable: " + destName);
                    }

                    DicomClient.StoreResult storeResult = client.store(study.getFiles());
                    success = storeResult.isSuccess();
                    filesTransferred = storeResult.getSuccessCount();
                    message = success ? "Sent all files" : "Sent " + storeResult.getSuccessCount() + "/" + study.getFileCount() + " files";
                } else if (dest instanceof AppConfig.FileDestination) {
                    DestinationManager.ForwardResult result = destinationManager.forwardToFile(destName, study.getFiles(), study.getStudyUid());
                    success = result.isSuccess();
                    filesTransferred = result.getSuccessCount();
                    message = success ? "Copied all files" : result.getErrorMessage();
                }

                long duration = System.currentTimeMillis() - startTime;
                TransferTracker.DestinationStatus destStatus = success ?
                        TransferTracker.DestinationStatus.SUCCESS :
                        TransferTracker.DestinationStatus.FAILED;
                transferTracker.updateDestinationResult(transferId, destName, destStatus, message, duration, filesTransferred);

                if (success) {
                    anySuccess = true;
                } else {
                    allSuccess = false;
                }
            } catch (Exception e) {
                log.error("Error forwarding to {}: {}", destName, e.getMessage(), e);
                transferTracker.updateDestinationResult(transferId, destName,
                        TransferTracker.DestinationStatus.FAILED, e.getMessage(), 0, 0);
                allSuccess = false;
            }
        }

        return allSuccess || anySuccess;
    }

    private List<File> scanForDicomFiles(File dir, boolean recursive) {
        List<File> results = new ArrayList<>();
        scanDirectory(dir, results, recursive);
        return results;
    }

    private void scanDirectory(File dir, List<File> results, boolean recursive) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isFile()) {
                if (isDicomFile(f)) {
                    results.add(f);
                }
            } else if (f.isDirectory() && recursive) {
                scanDirectory(f, results, recursive);
            }
        }
    }

    private boolean isDicomFile(File file) {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".dcm") || name.endsWith(".dicom")) {
            return true;
        }

        if (file.length() > 132) {
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
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

    private Map<String, List<File>> groupByStudyUid(List<File> files) {
        Map<String, List<File>> result = new LinkedHashMap<>();
        for (File file : files) {
            String studyUid = extractStudyUid(file);
            result.computeIfAbsent(studyUid, k -> new ArrayList<>()).add(file);
        }
        return result;
    }

    private String extractStudyUid(File file) {
        try (DicomInputStream dis = new DicomInputStream(file)) {
            Attributes attrs = dis.readDataset();
            String uid = attrs.getString(Tag.StudyInstanceUID);
            if (uid != null && !uid.isEmpty()) {
                return uid;
            }
        } catch (IOException e) {
            log.debug("Could not read Study UID from {}: {}", file.getName(), e.getMessage());
        }
        return file.getParentFile().getName() + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private File createZipFromFiles(List<File> files) throws IOException {
        java.nio.file.Path tempZip = Files.createTempFile("dicom_import_", ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tempZip))) {
            for (File file : files) {
                ZipEntry entry = new ZipEntry(file.getName());
                zos.putNextEntry(entry);
                Files.copy(file.toPath(), zos);
                zos.closeEntry();
            }
        }
        return tempZip.toFile();
    }

    private String extractPatientIdFromFiles(List<File> files) {
        if (files.isEmpty()) return "UNKNOWN";
        try (DicomInputStream dis = new DicomInputStream(files.get(0))) {
            Attributes attrs = dis.readDataset();
            return attrs.getString(Tag.PatientID, "UNKNOWN");
        } catch (IOException e) {
            return "UNKNOWN";
        }
    }

    private String generateSubjectIdFromFiles(List<File> files, String prefix) {
        if (prefix == null) prefix = "SUBJ";
        String patientId = extractPatientIdFromFiles(files);
        if (!"UNKNOWN".equals(patientId)) {
            return prefix + patientId;
        }
        return prefix + "_IMPORT_" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Import job tracking class.
     */
    public static class ImportJob {
        private final String id;
        private final String path;
        private final String route;
        private final boolean recursive;
        private final boolean moveFiles;
        private final long startTime;

        private volatile String status = "pending";
        private volatile int totalFiles = 0;
        private volatile int totalStudies = 0;
        private volatile int processedStudies = 0;
        private volatile int successCount = 0;
        private volatile int failCount = 0;
        private volatile String currentStudy = null;
        private volatile boolean cancelled = false;
        private final List<String> errors = Collections.synchronizedList(new ArrayList<>());

        public ImportJob(String id, String path, String route, boolean recursive, boolean moveFiles) {
            this.id = id;
            this.path = path;
            this.route = route;
            this.recursive = recursive;
            this.moveFiles = moveFiles;
            this.startTime = System.currentTimeMillis();
        }

        public String getId() { return id; }
        public String getPath() { return path; }
        public String getRoute() { return route; }
        public boolean isRecursive() { return recursive; }
        public boolean isMoveFiles() { return moveFiles; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public int getTotalFiles() { return totalFiles; }
        public void setTotalFiles(int totalFiles) { this.totalFiles = totalFiles; }
        public int getTotalStudies() { return totalStudies; }
        public void setTotalStudies(int totalStudies) { this.totalStudies = totalStudies; }
        public int getProcessedStudies() { return processedStudies; }
        public void incrementProcessedStudies() { this.processedStudies++; }
        public int getSuccessCount() { return successCount; }
        public void incrementSuccessCount() { this.successCount++; }
        public int getFailCount() { return failCount; }
        public void incrementFailCount() { this.failCount++; }
        public String getCurrentStudy() { return currentStudy; }
        public void setCurrentStudy(String currentStudy) { this.currentStudy = currentStudy; }
        public boolean isCancelled() { return cancelled; }
        public void cancel() { this.cancelled = true; }
        public void addError(String error) { this.errors.add(error); }
        public List<String> getErrors() { return new ArrayList<>(errors); }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("path", path);
            map.put("route", route);
            map.put("status", status);
            map.put("recursive", recursive);
            map.put("moveFiles", moveFiles);
            map.put("totalFiles", totalFiles);
            map.put("totalStudies", totalStudies);
            map.put("processedStudies", processedStudies);
            map.put("successCount", successCount);
            map.put("failCount", failCount);
            map.put("currentStudy", currentStudy);
            map.put("errors", errors);
            map.put("startTime", startTime);
            map.put("elapsedMs", System.currentTimeMillis() - startTime);
            return map;
        }
    }
}
