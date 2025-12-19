/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.api;

import io.xnatworks.router.config.AppConfig;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
// Using fully qualified names to avoid conflict with java.nio.file.Path
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.dcm4che3.imageio.plugins.dcm.DicomImageReadParam;
import org.dcm4che3.io.DicomInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * REST API for browsing storage/cache directories.
 */
@jakarta.ws.rs.Path("/storage")
@Produces(MediaType.APPLICATION_JSON)
public class StorageResource {
    private static final Logger log = LoggerFactory.getLogger(StorageResource.class);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final AppConfig config;

    public StorageResource(AppConfig config) {
        this.config = config;
    }

    /**
     * Get overview of all storage directories with statistics.
     */
    @GET
    public Response getStorageOverview() {
        Map<String, Object> overview = new LinkedHashMap<>();

        String dataDir = config.getDataDirectory();
        overview.put("dataDirectory", dataDir);

        // Get list of route directories (each route has its own folder)
        List<Map<String, Object>> routes = new ArrayList<>();
        Path dataPath = Paths.get(dataDir);

        if (Files.exists(dataPath) && Files.isDirectory(dataPath)) {
            try (Stream<Path> stream = Files.list(dataPath)) {
                stream.filter(Files::isDirectory)
                      .filter(p -> !p.getFileName().toString().startsWith("."))
                      .filter(p -> !p.getFileName().toString().equals("scripts"))
                      .forEach(routePath -> {
                          Map<String, Object> routeInfo = new LinkedHashMap<>();
                          String routeName = routePath.getFileName().toString();
                          routeInfo.put("name", routeName);
                          routeInfo.put("path", routePath.toString());

                          // Count files in each subdirectory
                          routeInfo.put("incoming", countFilesInDir(routePath.resolve("incoming")));
                          routeInfo.put("processing", countFilesInDir(routePath.resolve("processing")));
                          routeInfo.put("completed", countFilesInDir(routePath.resolve("completed")));
                          routeInfo.put("failed", countFilesInDir(routePath.resolve("failed")));
                          routeInfo.put("deleted", countFilesInDir(routePath.resolve("deleted")));

                          // Get disk usage
                          routeInfo.put("totalSize", formatSize(getDirSize(routePath)));
                          routeInfo.put("totalSizeBytes", getDirSize(routePath));

                          routes.add(routeInfo);
                      });
            } catch (IOException e) {
                log.error("Error listing data directory: {}", e.getMessage());
            }
        }

        overview.put("routes", routes);

        // Total statistics
        long totalFiles = routes.stream()
                .mapToLong(r -> ((Number) r.get("incoming")).longValue() +
                               ((Number) r.get("processing")).longValue() +
                               ((Number) r.get("completed")).longValue() +
                               ((Number) r.get("failed")).longValue())
                .sum();
        long totalSize = routes.stream()
                .mapToLong(r -> ((Number) r.get("totalSizeBytes")).longValue())
                .sum();

        overview.put("totalFiles", totalFiles);
        overview.put("totalSize", formatSize(totalSize));
        overview.put("totalSizeBytes", totalSize);

        return Response.ok(overview).build();
    }

    /**
     * Get contents of a specific route's storage directory.
     */
    @GET
    @jakarta.ws.rs.Path("/routes/{routeName}")
    public Response getRouteStorage(@PathParam("routeName") String routeName) {
        Path routePath = Paths.get(config.getDataDirectory(), routeName);

        if (!Files.exists(routePath)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Route storage not found: " + routeName))
                    .build();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("route", routeName);
        result.put("path", routePath.toString());

        // List all subdirectories and their contents
        for (String subDir : Arrays.asList("incoming", "processing", "completed", "failed", "deleted", "logs")) {
            Path subPath = routePath.resolve(subDir);
            if (Files.exists(subPath)) {
                result.put(subDir, listDirectory(subPath, true));
            } else {
                result.put(subDir, Map.of("exists", false, "items", Collections.emptyList()));
            }
        }

        return Response.ok(result).build();
    }

    /**
     * Browse a specific path within the data directory.
     */
    @GET
    @jakarta.ws.rs.Path("/browse")
    public Response browsePath(@QueryParam("path") String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            relativePath = "";
        }

        // Security: Ensure path stays within data directory
        Path basePath = Paths.get(config.getDataDirectory()).toAbsolutePath().normalize();
        Path targetPath = basePath.resolve(relativePath).normalize();

        if (!targetPath.startsWith(basePath)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Access denied: Path outside data directory"))
                    .build();
        }

        if (!Files.exists(targetPath)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Path not found: " + relativePath))
                    .build();
        }

        Map<String, Object> result = listDirectory(targetPath, false);
        result.put("currentPath", relativePath);
        result.put("absolutePath", targetPath.toString());

        // Add breadcrumbs for navigation
        List<Map<String, String>> breadcrumbs = new ArrayList<>();
        breadcrumbs.add(Map.of("name", "root", "path", ""));

        if (!relativePath.isEmpty()) {
            String[] parts = relativePath.split("/");
            StringBuilder pathBuilder = new StringBuilder();
            for (String part : parts) {
                if (!part.isEmpty()) {
                    if (pathBuilder.length() > 0) {
                        pathBuilder.append("/");
                    }
                    pathBuilder.append(part);
                    breadcrumbs.add(Map.of("name", part, "path", pathBuilder.toString()));
                }
            }
        }
        result.put("breadcrumbs", breadcrumbs);

        return Response.ok(result).build();
    }

    /**
     * Get details of a specific study directory.
     */
    @GET
    @jakarta.ws.rs.Path("/routes/{routeName}/studies/{studyFolder}")
    public Response getStudyDetails(@PathParam("routeName") String routeName,
                                    @PathParam("studyFolder") String studyFolder,
                                    @QueryParam("status") @DefaultValue("incoming") String status) {
        Path studyPath = Paths.get(config.getDataDirectory(), routeName, status, studyFolder);

        if (!Files.exists(studyPath)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Study folder not found"))
                    .build();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("route", routeName);
        result.put("status", status);
        result.put("studyFolder", studyFolder);
        result.put("path", studyPath.toString());

        // List DICOM files in the study
        List<Map<String, Object>> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(studyPath)) {
            stream.filter(Files::isRegularFile)
                  .forEach(filePath -> {
                      try {
                          BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
                          Map<String, Object> fileInfo = new LinkedHashMap<>();
                          fileInfo.put("name", filePath.getFileName().toString());
                          fileInfo.put("size", attrs.size());
                          fileInfo.put("sizeFormatted", formatSize(attrs.size()));
                          fileInfo.put("modified", DATE_FORMAT.format(attrs.lastModifiedTime().toInstant()));
                          files.add(fileInfo);
                      } catch (IOException e) {
                          log.debug("Error reading file attributes: {}", e.getMessage());
                      }
                  });
        } catch (IOException e) {
            log.error("Error listing study directory: {}", e.getMessage());
        }

        result.put("files", files);
        result.put("fileCount", files.size());
        result.put("totalSize", formatSize(files.stream().mapToLong(f -> ((Number) f.get("size")).longValue()).sum()));

        return Response.ok(result).build();
    }

    /**
     * Get DICOM header information for a specific file.
     */
    @GET
    @jakarta.ws.rs.Path("/dicom/header")
    public Response getDicomHeader(@QueryParam("path") String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Path parameter is required"))
                    .build();
        }

        // Security: Ensure path stays within data directory
        Path basePath = Paths.get(config.getDataDirectory()).toAbsolutePath().normalize();
        Path targetPath = basePath.resolve(relativePath).normalize();

        if (!targetPath.startsWith(basePath)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Access denied: Path outside data directory"))
                    .build();
        }

        if (!Files.exists(targetPath) || Files.isDirectory(targetPath)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "File not found: " + relativePath))
                    .build();
        }

        try (DicomInputStream dis = new DicomInputStream(targetPath.toFile())) {
            Attributes attrs = dis.readDataset();
            Attributes fmi = dis.readFileMetaInformation();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", relativePath);
            result.put("filename", targetPath.getFileName().toString());

            // Patient information
            Map<String, String> patient = new LinkedHashMap<>();
            patient.put("name", attrs.getString(Tag.PatientName, ""));
            patient.put("id", attrs.getString(Tag.PatientID, ""));
            patient.put("birthDate", attrs.getString(Tag.PatientBirthDate, ""));
            patient.put("sex", attrs.getString(Tag.PatientSex, ""));
            patient.put("age", attrs.getString(Tag.PatientAge, ""));
            result.put("patient", patient);

            // Study information
            Map<String, String> study = new LinkedHashMap<>();
            study.put("instanceUID", attrs.getString(Tag.StudyInstanceUID, ""));
            study.put("id", attrs.getString(Tag.StudyID, ""));
            study.put("date", attrs.getString(Tag.StudyDate, ""));
            study.put("time", attrs.getString(Tag.StudyTime, ""));
            study.put("description", attrs.getString(Tag.StudyDescription, ""));
            study.put("accessionNumber", attrs.getString(Tag.AccessionNumber, ""));
            result.put("study", study);

            // Series information
            Map<String, String> series = new LinkedHashMap<>();
            series.put("instanceUID", attrs.getString(Tag.SeriesInstanceUID, ""));
            series.put("number", attrs.getString(Tag.SeriesNumber, ""));
            series.put("description", attrs.getString(Tag.SeriesDescription, ""));
            series.put("modality", attrs.getString(Tag.Modality, ""));
            series.put("bodyPart", attrs.getString(Tag.BodyPartExamined, ""));
            result.put("series", series);

            // Instance information
            Map<String, String> instance = new LinkedHashMap<>();
            instance.put("sopInstanceUID", attrs.getString(Tag.SOPInstanceUID, ""));
            instance.put("sopClassUID", attrs.getString(Tag.SOPClassUID, ""));
            instance.put("instanceNumber", attrs.getString(Tag.InstanceNumber, ""));
            result.put("instance", instance);

            // Image information
            Map<String, Object> image = new LinkedHashMap<>();
            image.put("rows", attrs.getInt(Tag.Rows, 0));
            image.put("columns", attrs.getInt(Tag.Columns, 0));
            image.put("bitsAllocated", attrs.getInt(Tag.BitsAllocated, 0));
            image.put("bitsStored", attrs.getInt(Tag.BitsStored, 0));
            image.put("photometricInterpretation", attrs.getString(Tag.PhotometricInterpretation, ""));
            image.put("samplesPerPixel", attrs.getInt(Tag.SamplesPerPixel, 0));
            image.put("pixelSpacing", attrs.getStrings(Tag.PixelSpacing));
            image.put("sliceThickness", attrs.getString(Tag.SliceThickness, ""));
            result.put("image", image);

            // Equipment information
            Map<String, String> equipment = new LinkedHashMap<>();
            equipment.put("manufacturer", attrs.getString(Tag.Manufacturer, ""));
            equipment.put("institutionName", attrs.getString(Tag.InstitutionName, ""));
            equipment.put("stationName", attrs.getString(Tag.StationName, ""));
            equipment.put("modelName", attrs.getString(Tag.ManufacturerModelName, ""));
            result.put("equipment", equipment);

            // Transfer syntax from file meta info
            if (fmi != null) {
                result.put("transferSyntaxUID", fmi.getString(Tag.TransferSyntaxUID, ""));
            }

            // All tags (for advanced view)
            List<Map<String, Object>> allTags = new ArrayList<>();
            for (int tag : attrs.tags()) {
                VR vr = attrs.getVR(tag);
                Map<String, Object> tagInfo = new LinkedHashMap<>();
                tagInfo.put("tag", String.format("(%04X,%04X)", (tag >> 16) & 0xFFFF, tag & 0xFFFF));
                tagInfo.put("vr", vr != null ? vr.name() : "UN");
                tagInfo.put("keyword", org.dcm4che3.data.ElementDictionary.keywordOf(tag, null));

                // Get value (skip pixel data)
                if (tag != Tag.PixelData) {
                    Object value = attrs.getValue(tag);
                    if (value instanceof byte[]) {
                        tagInfo.put("value", "[binary data: " + ((byte[]) value).length + " bytes]");
                    } else if (value != null) {
                        String strValue = attrs.getString(tag, "");
                        // Truncate long values
                        if (strValue.length() > 200) {
                            strValue = strValue.substring(0, 200) + "...";
                        }
                        tagInfo.put("value", strValue);
                    } else {
                        tagInfo.put("value", "");
                    }
                } else {
                    tagInfo.put("value", "[pixel data]");
                }

                allTags.add(tagInfo);
            }
            result.put("allTags", allTags);

            return Response.ok(result).build();
        } catch (IOException e) {
            log.error("Error reading DICOM file: {}", e.getMessage());
            return Response.serverError()
                    .entity(Map.of("error", "Failed to read DICOM file: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Get DICOM image as PNG for preview.
     */
    @GET
    @jakarta.ws.rs.Path("/dicom/image")
    @Produces("image/png")
    public Response getDicomImage(@QueryParam("path") String relativePath,
                                  @QueryParam("frame") @DefaultValue("0") int frame) {
        if (relativePath == null || relativePath.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Path parameter is required")
                    .type(MediaType.TEXT_PLAIN)
                    .build();
        }

        // Security: Ensure path stays within data directory
        Path basePath = Paths.get(config.getDataDirectory()).toAbsolutePath().normalize();
        Path targetPath = basePath.resolve(relativePath).normalize();

        if (!targetPath.startsWith(basePath)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity("Access denied: Path outside data directory")
                    .type(MediaType.TEXT_PLAIN)
                    .build();
        }

        if (!Files.exists(targetPath) || Files.isDirectory(targetPath)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("File not found: " + relativePath)
                    .type(MediaType.TEXT_PLAIN)
                    .build();
        }

        try {
            // Read DICOM image using dcm4che imageio
            java.util.Iterator<ImageReader> iter = ImageIO.getImageReadersByFormatName("DICOM");
            if (!iter.hasNext()) {
                return Response.serverError()
                        .entity("DICOM ImageReader not available")
                        .type(MediaType.TEXT_PLAIN)
                        .build();
            }

            ImageReader reader = iter.next();
            try (ImageInputStream iis = ImageIO.createImageInputStream(targetPath.toFile())) {
                reader.setInput(iis);

                DicomImageReadParam param = (DicomImageReadParam) reader.getDefaultReadParam();
                BufferedImage image = reader.read(frame, param);

                if (image == null) {
                    return Response.status(Response.Status.NOT_FOUND)
                            .entity("Could not read image from DICOM file")
                            .type(MediaType.TEXT_PLAIN)
                            .build();
                }

                // Convert to PNG
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(image, "PNG", baos);

                return Response.ok(baos.toByteArray())
                        .header("Content-Disposition", "inline; filename=\"preview.png\"")
                        .build();
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            log.error("Error rendering DICOM image: {}", e.getMessage());
            return Response.serverError()
                    .entity("Failed to render DICOM image: " + e.getMessage())
                    .type(MediaType.TEXT_PLAIN)
                    .build();
        }
    }

    /**
     * Delete a study folder (only from failed or completed directories).
     */
    @DELETE
    @jakarta.ws.rs.Path("/routes/{routeName}/studies/{studyFolder}")
    public Response deleteStudy(@PathParam("routeName") String routeName,
                                @PathParam("studyFolder") String studyFolder,
                                @QueryParam("status") String status) {
        // Only allow deletion from failed or completed directories
        if (status == null || (!status.equals("failed") && !status.equals("completed"))) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Can only delete from 'failed' or 'completed' directories"))
                    .build();
        }

        Path studyPath = Paths.get(config.getDataDirectory(), routeName, status, studyFolder);

        if (!Files.exists(studyPath)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Study folder not found"))
                    .build();
        }

        try {
            // Delete all files in the directory
            Files.walk(studyPath)
                 .sorted(Comparator.reverseOrder())
                 .forEach(path -> {
                     try {
                         Files.delete(path);
                     } catch (IOException e) {
                         log.error("Error deleting {}: {}", path, e.getMessage());
                     }
                 });

            log.info("Deleted study folder: {}/{}/{}", routeName, status, studyFolder);
            return Response.ok(Map.of("message", "Study deleted successfully")).build();
        } catch (IOException e) {
            log.error("Error deleting study: {}", e.getMessage());
            return Response.serverError()
                    .entity(Map.of("error", "Failed to delete study: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Retry a failed study by moving it back to incoming.
     * Updates retry metadata to track retry count and history.
     */
    @POST
    @jakarta.ws.rs.Path("/routes/{routeName}/studies/{studyFolder}/retry")
    public Response retryStudy(@PathParam("routeName") String routeName,
                               @PathParam("studyFolder") String studyFolder) {
        Path failedPath = Paths.get(config.getDataDirectory(), routeName, "failed", studyFolder);
        Path incomingPath = Paths.get(config.getDataDirectory(), routeName, "incoming", studyFolder);

        if (!Files.exists(failedPath)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Failed study folder not found"))
                    .build();
        }

        if (Files.exists(incomingPath)) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "Study already exists in incoming directory"))
                    .build();
        }

        try {
            // Update retry metadata before moving
            updateRetryMetadata(failedPath);

            Files.move(failedPath, incomingPath, StandardCopyOption.ATOMIC_MOVE);
            log.info("Moved study from failed to incoming: {}/{}", routeName, studyFolder);
            return Response.ok(Map.of("message", "Study queued for retry")).build();
        } catch (IOException e) {
            log.error("Error moving study to retry: {}", e.getMessage());
            return Response.serverError()
                    .entity(Map.of("error", "Failed to retry study: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Get list of failed studies for a route, including retry metadata.
     */
    @GET
    @jakarta.ws.rs.Path("/routes/{routeName}/failed")
    public Response getFailedStudies(@PathParam("routeName") String routeName) {
        Path failedDir = Paths.get(config.getDataDirectory(), routeName, "failed");

        if (!Files.exists(failedDir)) {
            return Response.ok(Map.of(
                    "route", routeName,
                    "failedCount", 0,
                    "studies", Collections.emptyList()
            )).build();
        }

        List<Map<String, Object>> studies = new ArrayList<>();

        try (Stream<Path> stream = Files.list(failedDir)) {
            stream.filter(Files::isDirectory)
                  .filter(p -> p.getFileName().toString().startsWith("study_"))
                  .forEach(studyPath -> {
                      try {
                          BasicFileAttributes attrs = Files.readAttributes(studyPath, BasicFileAttributes.class);
                          Map<String, Object> studyInfo = new LinkedHashMap<>();
                          studyInfo.put("name", studyPath.getFileName().toString());
                          studyInfo.put("fileCount", countFilesInDir(studyPath));
                          studyInfo.put("totalSize", getDirSize(studyPath));
                          studyInfo.put("totalSizeFormatted", formatSize(getDirSize(studyPath)));
                          studyInfo.put("modified", DATE_FORMAT.format(attrs.lastModifiedTime().toInstant()));

                          // Read failure reason if available
                          Path failureReasonFile = studyPath.resolve("failure_reason.txt");
                          if (Files.exists(failureReasonFile)) {
                              try {
                                  studyInfo.put("failureReason", Files.readString(failureReasonFile).trim());
                              } catch (IOException e) {
                                  studyInfo.put("failureReason", "Unknown");
                              }
                          }

                          // Read retry metadata
                          Map<String, Object> retryMetadata = readRetryMetadata(studyPath);
                          studyInfo.put("retryCount", retryMetadata.get("retryCount"));
                          studyInfo.put("lastRetryAt", retryMetadata.get("lastRetryAt"));
                          studyInfo.put("retryHistory", retryMetadata.get("retryHistory"));

                          studies.add(studyInfo);
                      } catch (IOException e) {
                          log.debug("Error reading study info: {}", e.getMessage());
                      }
                  });
        } catch (IOException e) {
            log.error("Error listing failed directory: {}", e.getMessage());
            return Response.serverError()
                    .entity(Map.of("error", "Failed to list failed studies: " + e.getMessage()))
                    .build();
        }

        // Sort by modification time descending (most recent first)
        studies.sort((a, b) -> {
            String aTime = (String) a.get("modified");
            String bTime = (String) b.get("modified");
            return bTime.compareTo(aTime);
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("route", routeName);
        result.put("failedCount", studies.size());
        result.put("studies", studies);

        return Response.ok(result).build();
    }

    /**
     * Reprocess a completed study by moving it back to incoming.
     * Useful when the study was uploaded with wrong settings (e.g., missing project).
     */
    @POST
    @jakarta.ws.rs.Path("/routes/{routeName}/studies/{studyFolder}/reprocess")
    public Response reprocessStudy(@PathParam("routeName") String routeName,
                                   @PathParam("studyFolder") String studyFolder) {
        Path completedPath = Paths.get(config.getDataDirectory(), routeName, "completed", studyFolder);
        Path incomingPath = Paths.get(config.getDataDirectory(), routeName, "incoming", studyFolder);

        if (!Files.exists(completedPath)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Completed study folder not found"))
                    .build();
        }

        if (Files.exists(incomingPath)) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "Study already exists in incoming directory"))
                    .build();
        }

        try {
            Files.move(completedPath, incomingPath, StandardCopyOption.ATOMIC_MOVE);
            log.info("Moved study from completed to incoming for reprocessing: {}/{}", routeName, studyFolder);
            return Response.ok(Map.of("message", "Study queued for reprocessing")).build();
        } catch (IOException e) {
            log.error("Error moving study for reprocessing: {}", e.getMessage());
            return Response.serverError()
                    .entity(Map.of("error", "Failed to reprocess study: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Retry all failed studies for a route by moving them back to incoming.
     * Updates retry metadata to track retry count and history.
     */
    @POST
    @jakarta.ws.rs.Path("/routes/{routeName}/retry-all")
    public Response retryAllFailed(@PathParam("routeName") String routeName) {
        Path failedDir = Paths.get(config.getDataDirectory(), routeName, "failed");
        Path incomingDir = Paths.get(config.getDataDirectory(), routeName, "incoming");

        if (!Files.exists(failedDir)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Failed directory not found for route: " + routeName))
                    .build();
        }

        List<String> moved = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        try {
            // Ensure incoming directory exists
            Files.createDirectories(incomingDir);

            try (Stream<Path> stream = Files.list(failedDir)) {
                stream.filter(Files::isDirectory)
                      .filter(p -> p.getFileName().toString().startsWith("study_"))
                      .forEach(studyPath -> {
                          String studyFolder = studyPath.getFileName().toString();
                          Path targetPath = incomingDir.resolve(studyFolder);

                          if (Files.exists(targetPath)) {
                              skipped.add(studyFolder);
                              return;
                          }

                          try {
                              // Update retry metadata before moving
                              updateRetryMetadata(studyPath);

                              Files.move(studyPath, targetPath, StandardCopyOption.ATOMIC_MOVE);
                              moved.add(studyFolder);
                              log.info("Moved study from failed to incoming for retry: {}/{}", routeName, studyFolder);
                          } catch (IOException e) {
                              errors.add(studyFolder + ": " + e.getMessage());
                              log.error("Error moving study {}: {}", studyFolder, e.getMessage());
                          }
                      });
            }
        } catch (IOException e) {
            log.error("Error processing failed directory: {}", e.getMessage());
            return Response.serverError()
                    .entity(Map.of("error", "Failed to process failed directory: " + e.getMessage()))
                    .build();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("moved", moved.size());
        result.put("skipped", skipped.size());
        result.put("errors", errors.size());
        result.put("movedStudies", moved);
        if (!skipped.isEmpty()) {
            result.put("skippedStudies", skipped);
        }
        if (!errors.isEmpty()) {
            result.put("errorDetails", errors);
        }
        result.put("message", String.format("Moved %d failed studies for retry (%d skipped, %d errors)",
                moved.size(), skipped.size(), errors.size()));

        return Response.ok(result).build();
    }

    /**
     * Move all storage for a route to the 'deleted' folder (soft delete).
     * Studies in the deleted folder can be purged later via retention policy.
     */
    @DELETE
    @jakarta.ws.rs.Path("/routes/{routeName}/all")
    public Response deleteAllStorage(@PathParam("routeName") String routeName) {
        Path routePath = Paths.get(config.getDataDirectory(), routeName);

        if (!Files.exists(routePath)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Route storage not found: " + routeName))
                    .build();
        }

        // Create deleted directory if it doesn't exist
        Path deletedDir = routePath.resolve("deleted");
        try {
            Files.createDirectories(deletedDir);
        } catch (IOException e) {
            log.error("Failed to create deleted directory: {}", e.getMessage());
            return Response.serverError()
                    .entity(Map.of("error", "Failed to create deleted directory: " + e.getMessage()))
                    .build();
        }

        List<String> movedDirs = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        long totalFilesMoved = 0;
        long totalBytesMoved = 0;

        // Move contents of each subdirectory to deleted folder
        for (String subDir : Arrays.asList("incoming", "processing", "completed", "failed")) {
            Path subPath = routePath.resolve(subDir);
            if (Files.exists(subPath)) {
                try {
                    long fileCount = countFilesInDir(subPath);
                    long dirSize = getDirSize(subPath);

                    // Move all study folders to deleted directory (with timestamp prefix to avoid conflicts)
                    String timestamp = java.time.LocalDateTime.now().format(
                            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

                    try (Stream<Path> stream = Files.list(subPath)) {
                        stream.filter(Files::isDirectory)
                              .forEach(studyPath -> {
                                  String studyName = studyPath.getFileName().toString();
                                  // Prefix with source folder and timestamp to avoid collisions
                                  String deletedName = subDir + "_" + timestamp + "_" + studyName;
                                  Path targetPath = deletedDir.resolve(deletedName);

                                  try {
                                      Files.move(studyPath, targetPath, StandardCopyOption.ATOMIC_MOVE);
                                      log.debug("Moved {} to deleted: {}", studyPath, targetPath);
                                  } catch (IOException e) {
                                      log.error("Error moving {} to deleted: {}", studyPath, e.getMessage());
                                  }
                              });
                    }

                    totalFilesMoved += fileCount;
                    totalBytesMoved += dirSize;
                    movedDirs.add(subDir + " (" + fileCount + " files, " + formatSize(dirSize) + ")");
                    log.info("Moved {}/{} to deleted: {} files, {}", routeName, subDir, fileCount, formatSize(dirSize));
                } catch (IOException e) {
                    errors.add(subDir + ": " + e.getMessage());
                    log.error("Error moving {}/{} to deleted: {}", routeName, subDir, e.getMessage());
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "Storage moved to deleted for route: " + routeName);
        result.put("route", routeName);
        result.put("totalFilesMoved", totalFilesMoved);
        result.put("totalBytesMoved", totalBytesMoved);
        result.put("totalSizeMoved", formatSize(totalBytesMoved));
        result.put("movedDirectories", movedDirs);
        result.put("deletedFolder", deletedDir.toString());
        if (!errors.isEmpty()) {
            result.put("errors", errors);
        }

        log.info("Moved all storage for route {} to deleted: {} files, {}", routeName, totalFilesMoved, formatSize(totalBytesMoved));
        return Response.ok(result).build();
    }

    /**
     * Permanently purge all data from the deleted folder for a route.
     * This is a truly destructive operation.
     */
    @DELETE
    @jakarta.ws.rs.Path("/routes/{routeName}/deleted/purge")
    public Response purgeDeletedStorage(@PathParam("routeName") String routeName) {
        Path deletedDir = Paths.get(config.getDataDirectory(), routeName, "deleted");

        if (!Files.exists(deletedDir)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Deleted folder not found for route: " + routeName))
                    .build();
        }

        long fileCount = countFilesInDir(deletedDir);
        long dirSize = getDirSize(deletedDir);

        try {
            // Delete all contents of the deleted folder
            try (Stream<Path> stream = Files.list(deletedDir)) {
                stream.forEach(path -> {
                    try {
                        if (Files.isDirectory(path)) {
                            Files.walk(path)
                                 .sorted(Comparator.reverseOrder())
                                 .forEach(p -> {
                                     try {
                                         Files.delete(p);
                                     } catch (IOException e) {
                                         log.error("Error purging {}: {}", p, e.getMessage());
                                     }
                                 });
                        } else {
                            Files.delete(path);
                        }
                    } catch (IOException e) {
                        log.error("Error purging {}: {}", path, e.getMessage());
                    }
                });
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("message", "Deleted folder purged for route: " + routeName);
            result.put("route", routeName);
            result.put("filesPurged", fileCount);
            result.put("sizePurged", formatSize(dirSize));

            log.info("Purged deleted folder for route {}: {} files, {}", routeName, fileCount, formatSize(dirSize));
            return Response.ok(result).build();
        } catch (IOException e) {
            log.error("Error purging deleted folder: {}", e.getMessage());
            return Response.serverError()
                    .entity(Map.of("error", "Failed to purge deleted folder: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Purge old items from deleted folder based on retention days.
     * Items older than the specified days will be permanently deleted.
     */
    @DELETE
    @jakarta.ws.rs.Path("/routes/{routeName}/deleted/purge-old")
    public Response purgeOldDeletedStorage(@PathParam("routeName") String routeName,
                                            @QueryParam("days") @DefaultValue("7") int retentionDays) {
        Path deletedDir = Paths.get(config.getDataDirectory(), routeName, "deleted");

        if (!Files.exists(deletedDir)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Deleted folder not found for route: " + routeName))
                    .build();
        }

        java.time.Instant cutoff = java.time.Instant.now().minus(java.time.Duration.ofDays(retentionDays));
        List<String> purged = new ArrayList<>();
        List<String> retained = new ArrayList<>();
        long totalFilesPurged = 0;
        long totalBytesPurged = 0;

        try (Stream<Path> stream = Files.list(deletedDir)) {
            List<Path> items = stream.filter(Files::isDirectory).collect(java.util.stream.Collectors.toList());

            for (Path itemPath : items) {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(itemPath, BasicFileAttributes.class);
                    if (attrs.lastModifiedTime().toInstant().isBefore(cutoff)) {
                        // Old enough to purge
                        long fileCount = countFilesInDir(itemPath);
                        long dirSize = getDirSize(itemPath);

                        Files.walk(itemPath)
                             .sorted(Comparator.reverseOrder())
                             .forEach(p -> {
                                 try {
                                     Files.delete(p);
                                 } catch (IOException e) {
                                     log.error("Error purging {}: {}", p, e.getMessage());
                                 }
                             });

                        totalFilesPurged += fileCount;
                        totalBytesPurged += dirSize;
                        purged.add(itemPath.getFileName().toString());
                        log.debug("Purged old deleted item: {}", itemPath.getFileName());
                    } else {
                        retained.add(itemPath.getFileName().toString());
                    }
                } catch (IOException e) {
                    log.error("Error checking/purging {}: {}", itemPath, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("Error listing deleted folder: {}", e.getMessage());
            return Response.serverError()
                    .entity(Map.of("error", "Failed to list deleted folder: " + e.getMessage()))
                    .build();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", String.format("Purged items older than %d days from deleted folder", retentionDays));
        result.put("route", routeName);
        result.put("retentionDays", retentionDays);
        result.put("itemsPurged", purged.size());
        result.put("itemsRetained", retained.size());
        result.put("filesPurged", totalFilesPurged);
        result.put("sizePurged", formatSize(totalBytesPurged));

        log.info("Purged {} old items from deleted folder for route {} (retention: {} days): {} files, {}",
                purged.size(), routeName, retentionDays, totalFilesPurged, formatSize(totalBytesPurged));
        return Response.ok(result).build();
    }

    /**
     * Reprocess all completed studies for a route by moving them back to incoming.
     */
    @POST
    @jakarta.ws.rs.Path("/routes/{routeName}/reprocess-all")
    public Response reprocessAllCompleted(@PathParam("routeName") String routeName) {
        Path completedDir = Paths.get(config.getDataDirectory(), routeName, "completed");
        Path incomingDir = Paths.get(config.getDataDirectory(), routeName, "incoming");

        if (!Files.exists(completedDir)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Completed directory not found for route: " + routeName))
                    .build();
        }

        List<String> moved = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        try (Stream<Path> stream = Files.list(completedDir)) {
            stream.filter(Files::isDirectory)
                  .filter(p -> p.getFileName().toString().startsWith("study_"))
                  .forEach(studyPath -> {
                      String studyFolder = studyPath.getFileName().toString();
                      Path targetPath = incomingDir.resolve(studyFolder);

                      if (Files.exists(targetPath)) {
                          skipped.add(studyFolder);
                          return;
                      }

                      try {
                          Files.move(studyPath, targetPath, StandardCopyOption.ATOMIC_MOVE);
                          moved.add(studyFolder);
                          log.info("Moved study from completed to incoming: {}/{}", routeName, studyFolder);
                      } catch (IOException e) {
                          errors.add(studyFolder + ": " + e.getMessage());
                          log.error("Error moving study {}: {}", studyFolder, e.getMessage());
                      }
                  });
        } catch (IOException e) {
            log.error("Error listing completed directory: {}", e.getMessage());
            return Response.serverError()
                    .entity(Map.of("error", "Failed to list completed directory: " + e.getMessage()))
                    .build();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("moved", moved.size());
        result.put("skipped", skipped.size());
        result.put("errors", errors.size());
        result.put("movedStudies", moved);
        if (!skipped.isEmpty()) {
            result.put("skippedStudies", skipped);
        }
        if (!errors.isEmpty()) {
            result.put("errorDetails", errors);
        }
        result.put("message", String.format("Moved %d studies for reprocessing (%d skipped, %d errors)",
                moved.size(), skipped.size(), errors.size()));

        return Response.ok(result).build();
    }

    /**
     * Upload files to a route's incoming directory.
     * Supports:
     * - Single DICOM files
     * - Multiple files
     * - ZIP archives (extracted automatically)
     * - TAR archives (extracted automatically)
     * - TAR.GZ archives (extracted automatically)
     */
    @POST
    @jakarta.ws.rs.Path("/routes/{routeName}/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadFiles(@PathParam("routeName") String routeName,
                                FormDataMultiPart multiPart) {
        Path routePath = Paths.get(config.getDataDirectory(), routeName);

        if (!Files.exists(routePath)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Route not found: " + routeName))
                    .build();
        }

        Path incomingDir = routePath.resolve("incoming");
        try {
            Files.createDirectories(incomingDir);
        } catch (IOException e) {
            log.error("Failed to create incoming directory: {}", e.getMessage());
            return Response.serverError()
                    .entity(Map.of("error", "Failed to create incoming directory"))
                    .build();
        }

        // Generate a unique study folder name
        String studyFolder = "study_" + UUID.randomUUID().toString().substring(0, 8) + "_" + System.currentTimeMillis();
        Path studyPath = incomingDir.resolve(studyFolder);

        try {
            Files.createDirectories(studyPath);
        } catch (IOException e) {
            log.error("Failed to create study folder: {}", e.getMessage());
            return Response.serverError()
                    .entity(Map.of("error", "Failed to create study folder"))
                    .build();
        }

        List<String> uploadedFiles = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int totalFilesExtracted = 0;

        // Process each uploaded file
        List<FormDataBodyPart> fileParts = multiPart.getFields("files");
        if (fileParts == null || fileParts.isEmpty()) {
            // Try single file field name
            fileParts = multiPart.getFields("file");
        }

        if (fileParts == null || fileParts.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "No files uploaded. Use 'files' or 'file' field name."))
                    .build();
        }

        for (FormDataBodyPart filePart : fileParts) {
            String fileName = filePart.getContentDisposition().getFileName();
            if (fileName == null || fileName.isEmpty()) {
                continue;
            }

            try (InputStream fileStream = filePart.getValueAs(InputStream.class)) {
                String lowerName = fileName.toLowerCase();

                if (lowerName.endsWith(".zip")) {
                    // Extract ZIP archive
                    int count = extractZip(fileStream, studyPath);
                    totalFilesExtracted += count;
                    uploadedFiles.add(fileName + " (" + count + " files extracted)");
                } else if (lowerName.endsWith(".tar.gz") || lowerName.endsWith(".tgz")) {
                    // Extract TAR.GZ archive
                    int count = extractTarGz(fileStream, studyPath);
                    totalFilesExtracted += count;
                    uploadedFiles.add(fileName + " (" + count + " files extracted)");
                } else if (lowerName.endsWith(".tar")) {
                    // Extract TAR archive
                    int count = extractTar(fileStream, studyPath);
                    totalFilesExtracted += count;
                    uploadedFiles.add(fileName + " (" + count + " files extracted)");
                } else {
                    // Regular file - copy directly
                    Path targetFile = studyPath.resolve(fileName);
                    Files.copy(fileStream, targetFile, StandardCopyOption.REPLACE_EXISTING);
                    uploadedFiles.add(fileName);
                    totalFilesExtracted++;
                }
            } catch (Exception e) {
                log.error("Error processing uploaded file {}: {}", fileName, e.getMessage());
                errors.add(fileName + ": " + e.getMessage());
            }
        }

        // If no files were uploaded, clean up the empty folder
        if (totalFilesExtracted == 0) {
            try {
                Files.deleteIfExists(studyPath);
            } catch (IOException e) {
                log.debug("Failed to delete empty study folder: {}", e.getMessage());
            }

            if (!errors.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "All uploads failed", "errors", errors))
                        .build();
            }
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "No files were uploaded"))
                    .build();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "Upload successful");
        result.put("route", routeName);
        result.put("studyFolder", studyFolder);
        result.put("filesUploaded", uploadedFiles.size());
        result.put("totalFiles", totalFilesExtracted);
        result.put("uploadedFiles", uploadedFiles);
        if (!errors.isEmpty()) {
            result.put("errors", errors);
        }

        log.info("Uploaded {} files to route {} in folder {}", totalFilesExtracted, routeName, studyFolder);
        return Response.ok(result).build();
    }

    /**
     * Move a study folder to import for processing.
     * This triggers the import workflow to route the study to destinations.
     */
    @POST
    @jakarta.ws.rs.Path("/routes/{routeName}/studies/{studyFolder}/move-to-import")
    public Response moveToImport(@PathParam("routeName") String routeName,
                                  @PathParam("studyFolder") String studyFolder,
                                  @QueryParam("status") @DefaultValue("incoming") String status) {
        Path sourcePath = Paths.get(config.getDataDirectory(), routeName, status, studyFolder);

        if (!Files.exists(sourcePath)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Study folder not found: " + studyFolder))
                    .build();
        }

        // Find the route config
        AppConfig.RouteConfig route = config.findRouteByAeTitle(routeName);
        if (route == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Route not found: " + routeName))
                    .build();
        }

        // Build import request and call import API internally
        Map<String, Object> importRequest = new LinkedHashMap<>();
        importRequest.put("path", sourcePath.toString());
        importRequest.put("route", routeName);
        importRequest.put("recursive", true);
        importRequest.put("moveFiles", true);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "Study queued for import processing");
        result.put("route", routeName);
        result.put("studyFolder", studyFolder);
        result.put("sourcePath", sourcePath.toString());
        result.put("importPath", sourcePath.toString());

        log.info("Queued study {} from route {} for import processing", studyFolder, routeName);
        return Response.accepted(result).build();
    }

    /**
     * Get route configuration including autoImport setting.
     */
    @GET
    @jakarta.ws.rs.Path("/routes/{routeName}/config")
    public Response getRouteConfig(@PathParam("routeName") String routeName) {
        AppConfig.RouteConfig route = config.findRouteByAeTitle(routeName);
        if (route == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Route not found: " + routeName))
                    .build();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("aeTitle", route.getAeTitle());
        result.put("port", route.getPort());
        result.put("enabled", route.isEnabled());
        result.put("autoImport", route.isAutoImport());
        result.put("destinationCount", route.getDestinations().size());

        return Response.ok(result).build();
    }

    /**
     * Extract ZIP archive to target directory.
     */
    private int extractZip(InputStream inputStream, Path targetDir) throws IOException {
        int count = 0;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(inputStream))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }

                String name = entry.getName();
                // Skip hidden files and __MACOSX
                if (name.startsWith("__MACOSX") || name.contains("/.__") || name.startsWith(".")) {
                    continue;
                }

                // Security: Prevent path traversal
                Path targetFile = targetDir.resolve(name).normalize();
                if (!targetFile.startsWith(targetDir)) {
                    log.warn("Skipping file outside target directory: {}", name);
                    continue;
                }

                // Flatten directory structure - put all files in root
                String fileName = targetFile.getFileName().toString();
                targetFile = targetDir.resolve(fileName);

                // Handle duplicate file names
                if (Files.exists(targetFile)) {
                    String baseName = fileName;
                    String extension = "";
                    int dotIndex = fileName.lastIndexOf('.');
                    if (dotIndex > 0) {
                        baseName = fileName.substring(0, dotIndex);
                        extension = fileName.substring(dotIndex);
                    }
                    int counter = 1;
                    while (Files.exists(targetFile)) {
                        targetFile = targetDir.resolve(baseName + "_" + counter + extension);
                        counter++;
                    }
                }

                Files.copy(zis, targetFile);
                count++;
            }
        }
        return count;
    }

    /**
     * Extract TAR archive to target directory.
     */
    private int extractTar(InputStream inputStream, Path targetDir) throws IOException {
        int count = 0;
        try (TarArchiveInputStream tis = new TarArchiveInputStream(new BufferedInputStream(inputStream))) {
            TarArchiveEntry entry;
            while ((entry = tis.getNextTarEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }

                String name = entry.getName();
                // Skip hidden files
                if (name.startsWith(".") || name.contains("/.")) {
                    continue;
                }

                // Security: Prevent path traversal
                Path targetFile = targetDir.resolve(name).normalize();
                if (!targetFile.startsWith(targetDir)) {
                    log.warn("Skipping file outside target directory: {}", name);
                    continue;
                }

                // Flatten directory structure
                String fileName = targetFile.getFileName().toString();
                targetFile = targetDir.resolve(fileName);

                // Handle duplicate file names
                if (Files.exists(targetFile)) {
                    String baseName = fileName;
                    String extension = "";
                    int dotIndex = fileName.lastIndexOf('.');
                    if (dotIndex > 0) {
                        baseName = fileName.substring(0, dotIndex);
                        extension = fileName.substring(dotIndex);
                    }
                    int counter = 1;
                    while (Files.exists(targetFile)) {
                        targetFile = targetDir.resolve(baseName + "_" + counter + extension);
                        counter++;
                    }
                }

                Files.copy(tis, targetFile);
                count++;
            }
        }
        return count;
    }

    /**
     * Extract TAR.GZ archive to target directory.
     */
    private int extractTarGz(InputStream inputStream, Path targetDir) throws IOException {
        try (GZIPInputStream gzis = new GZIPInputStream(new BufferedInputStream(inputStream))) {
            return extractTar(gzis, targetDir);
        }
    }

    // Helper methods

    private Map<String, Object> listDirectory(Path dirPath, boolean includeSubdirs) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exists", Files.exists(dirPath));

        if (!Files.exists(dirPath)) {
            result.put("items", Collections.emptyList());
            return result;
        }

        List<Map<String, Object>> items = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dirPath)) {
            stream.forEach(path -> {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", path.getFileName().toString());
                    item.put("isDirectory", Files.isDirectory(path));
                    item.put("size", attrs.size());
                    item.put("sizeFormatted", formatSize(attrs.size()));
                    item.put("modified", DATE_FORMAT.format(attrs.lastModifiedTime().toInstant()));

                    if (Files.isDirectory(path)) {
                        item.put("fileCount", countFilesInDir(path));
                        if (includeSubdirs) {
                            item.put("totalSize", getDirSize(path));
                            item.put("totalSizeFormatted", formatSize(getDirSize(path)));
                        }
                    }

                    items.add(item);
                } catch (IOException e) {
                    log.debug("Error reading attributes for {}: {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.error("Error listing directory: {}", e.getMessage());
        }

        // Sort: directories first, then by name
        items.sort((a, b) -> {
            boolean aDir = (boolean) a.get("isDirectory");
            boolean bDir = (boolean) b.get("isDirectory");
            if (aDir != bDir) {
                return aDir ? -1 : 1;
            }
            return ((String) a.get("name")).compareTo((String) b.get("name"));
        });

        result.put("items", items);
        result.put("itemCount", items.size());

        return result;
    }

    private long countFilesInDir(Path dir) {
        if (!Files.exists(dir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private long getDirSize(Path dir) {
        if (!Files.exists(dir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                        .mapToLong(p -> {
                            try {
                                return Files.size(p);
                            } catch (IOException e) {
                                return 0;
                            }
                        })
                        .sum();
        } catch (IOException e) {
            return 0;
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }

    private static final String RETRY_METADATA_FILE = "retry_metadata.json";

    /**
     * Update retry metadata for a study folder (increment count and add timestamp).
     */
    private void updateRetryMetadata(Path studyPath) throws IOException {
        Path metadataFile = studyPath.resolve(RETRY_METADATA_FILE);

        int retryCount = 1;
        List<String> retryHistory = new ArrayList<>();
        String lastError = null;

        // Read existing metadata if present
        if (Files.exists(metadataFile)) {
            try {
                String content = Files.readString(metadataFile);
                // Simple JSON parsing
                if (content.contains("\"retryCount\":")) {
                    String countStr = content.split("\"retryCount\":")[1].split("[,}]")[0].trim();
                    retryCount = Integer.parseInt(countStr) + 1;
                }
                if (content.contains("\"retryHistory\":")) {
                    String historyStr = content.split("\"retryHistory\":\\[")[1].split("]")[0];
                    if (!historyStr.trim().isEmpty()) {
                        for (String ts : historyStr.split(",")) {
                            retryHistory.add(ts.trim().replace("\"", ""));
                        }
                    }
                }
                if (content.contains("\"lastError\":")) {
                    String errorPart = content.split("\"lastError\":\"")[1];
                    lastError = errorPart.split("\"")[0];
                }
            } catch (Exception e) {
                log.warn("Failed to parse existing retry metadata, starting fresh: {}", e.getMessage());
                retryCount = 1;
                retryHistory.clear();
            }
        }

        // Read failure reason if available
        Path failureReasonFile = studyPath.resolve("failure_reason.txt");
        if (Files.exists(failureReasonFile)) {
            try {
                lastError = Files.readString(failureReasonFile).trim().replace("\"", "'").replace("\n", " ");
            } catch (IOException e) {
                log.debug("Failed to read failure reason: {}", e.getMessage());
            }
        }

        // Add current timestamp to history
        String now = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        retryHistory.add(now);

        // Write updated metadata
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"retryCount\": ").append(retryCount).append(",\n");
        json.append("  \"lastRetryAt\": \"").append(now).append("\",\n");
        json.append("  \"retryHistory\": [");
        for (int i = 0; i < retryHistory.size(); i++) {
            if (i > 0) json.append(", ");
            json.append("\"").append(retryHistory.get(i)).append("\"");
        }
        json.append("],\n");
        if (lastError != null) {
            json.append("  \"lastError\": \"").append(lastError).append("\"\n");
        } else {
            json.append("  \"lastError\": null\n");
        }
        json.append("}\n");

        Files.writeString(metadataFile, json.toString());
        log.debug("Updated retry metadata for {}: retry #{}", studyPath.getFileName(), retryCount);
    }

    /**
     * Read retry metadata for a study folder.
     */
    private Map<String, Object> readRetryMetadata(Path studyPath) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("retryCount", 0);
        metadata.put("lastRetryAt", null);
        metadata.put("retryHistory", Collections.emptyList());
        metadata.put("lastError", null);

        Path metadataFile = studyPath.resolve(RETRY_METADATA_FILE);
        if (!Files.exists(metadataFile)) {
            return metadata;
        }

        try {
            String content = Files.readString(metadataFile);

            if (content.contains("\"retryCount\":")) {
                String countStr = content.split("\"retryCount\":")[1].split("[,}]")[0].trim();
                metadata.put("retryCount", Integer.parseInt(countStr));
            }
            if (content.contains("\"lastRetryAt\":\"")) {
                String lastRetry = content.split("\"lastRetryAt\":\"")[1].split("\"")[0];
                metadata.put("lastRetryAt", lastRetry);
            }
            if (content.contains("\"retryHistory\":")) {
                String historyStr = content.split("\"retryHistory\":\\[")[1].split("]")[0];
                List<String> history = new ArrayList<>();
                if (!historyStr.trim().isEmpty()) {
                    for (String ts : historyStr.split(",")) {
                        history.add(ts.trim().replace("\"", ""));
                    }
                }
                metadata.put("retryHistory", history);
            }
            if (content.contains("\"lastError\":\"")) {
                String error = content.split("\"lastError\":\"")[1].split("\"")[0];
                metadata.put("lastError", error);
            }
        } catch (Exception e) {
            log.debug("Failed to read retry metadata: {}", e.getMessage());
        }

        return metadata;
    }
}
