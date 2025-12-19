/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.api;

import io.xnatworks.router.config.AppConfig;
import io.xnatworks.router.ocr.DetectedRegion;
import io.xnatworks.router.review.DicomComparisonService;
import io.xnatworks.router.review.model.*;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * REST API for DICOM comparison and review.
 *
 * Endpoints:
 * - GET  /compare/{aeTitle}/{studyUid}          - Get study comparison metadata
 * - GET  /compare/{aeTitle}/{studyUid}/scans    - Get scan comparisons for a study
 * - GET  /compare/header                        - Compare headers of two files
 * - GET  /compare/image                         - Get rendered image (with optional OCR overlay)
 * - GET  /compare/ocr                           - Get OCR detected regions
 */
@jakarta.ws.rs.Path("/compare")
@Produces(MediaType.APPLICATION_JSON)
@SuppressWarnings("unused")
public class DicomCompareResource {
    private static final Logger log = LoggerFactory.getLogger(DicomCompareResource.class);

    private final DicomComparisonService comparisonService;
    private final AppConfig config;

    public DicomCompareResource(DicomComparisonService comparisonService, AppConfig config) {
        this.comparisonService = comparisonService;
        this.config = config;
    }

    /**
     * Get study comparison metadata.
     */
    @GET
    @jakarta.ws.rs.Path("/{aeTitle}/{studyUid}")
    public Response getStudyComparison(
            @PathParam("aeTitle") String aeTitle,
            @PathParam("studyUid") String studyUid) {

        if (comparisonService == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "Comparison service not available"))
                    .build();
        }

        try {
            StudyComparison comparison = comparisonService.getStudyComparison(aeTitle, studyUid);
            return Response.ok(comparison).build();
        } catch (Exception e) {
            log.error("Failed to get study comparison for {}/{}: {}", aeTitle, studyUid, e.getMessage(), e);
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Study not found: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Get scan comparisons for a study.
     */
    @GET
    @jakarta.ws.rs.Path("/{aeTitle}/{studyUid}/scans")
    public Response getScanComparisons(
            @PathParam("aeTitle") String aeTitle,
            @PathParam("studyUid") String studyUid) {

        if (comparisonService == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "Comparison service not available"))
                    .build();
        }

        try {
            List<ScanComparison> scans = comparisonService.getScanComparisons(aeTitle, studyUid);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("aeTitle", aeTitle);
            result.put("studyUid", studyUid);
            result.put("scanCount", scans.size());
            result.put("scans", scans);

            return Response.ok(result).build();
        } catch (Exception e) {
            log.error("Failed to get scan comparisons for {}/{}: {}", aeTitle, studyUid, e.getMessage(), e);
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Study not found: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Compare headers between original and anonymized files.
     */
    @GET
    @jakarta.ws.rs.Path("/header")
    public Response compareHeaders(
            @QueryParam("original") String originalPath,
            @QueryParam("anonymized") String anonymizedPath) {

        if (originalPath == null || originalPath.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Original path is required"))
                    .build();
        }

        if (anonymizedPath == null || anonymizedPath.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Anonymized path is required"))
                    .build();
        }

        if (comparisonService == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "Comparison service not available"))
                    .build();
        }

        // Validate paths are within data directory
        Path basePath = Paths.get(config.getDataDirectory()).toAbsolutePath().normalize();
        Path origPath = Paths.get(originalPath).toAbsolutePath().normalize();
        Path anonPath = Paths.get(anonymizedPath).toAbsolutePath().normalize();

        if (!origPath.startsWith(basePath) || !anonPath.startsWith(basePath)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Access denied: Path outside data directory"))
                    .build();
        }

        try {
            HeaderComparison comparison = comparisonService.compareHeaders(origPath, anonPath);
            return Response.ok(comparison).build();
        } catch (Exception e) {
            log.error("Failed to compare headers: {}", e.getMessage(), e);
            return Response.serverError()
                    .entity(Map.of("error", "Failed to compare headers: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Get rendered DICOM image as PNG with optional OCR overlay.
     */
    @GET
    @jakarta.ws.rs.Path("/image")
    @Produces("image/png")
    public Response getImage(
            @QueryParam("path") String filePath,
            @QueryParam("overlay") @DefaultValue("false") boolean overlay,
            @QueryParam("frame") @DefaultValue("0") int frame) {

        if (filePath == null || filePath.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Path parameter is required")
                    .type(MediaType.TEXT_PLAIN)
                    .build();
        }

        if (comparisonService == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("Comparison service not available")
                    .type(MediaType.TEXT_PLAIN)
                    .build();
        }

        // Validate path is within data directory
        Path basePath = Paths.get(config.getDataDirectory()).toAbsolutePath().normalize();
        Path targetPath = Paths.get(filePath).toAbsolutePath().normalize();

        if (!targetPath.startsWith(basePath)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity("Access denied: Path outside data directory")
                    .type(MediaType.TEXT_PLAIN)
                    .build();
        }

        if (!Files.exists(targetPath)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("File not found")
                    .type(MediaType.TEXT_PLAIN)
                    .build();
        }

        try {
            byte[] imageBytes = comparisonService.renderImage(targetPath, overlay, frame);
            return Response.ok(imageBytes)
                    .header("Content-Disposition", "inline; filename=\"image.png\"")
                    .build();
        } catch (Exception e) {
            log.error("Failed to render image: {}", e.getMessage(), e);
            return Response.serverError()
                    .entity("Failed to render image: " + e.getMessage())
                    .type(MediaType.TEXT_PLAIN)
                    .build();
        }
    }

    /**
     * Get OCR detected regions for a DICOM file.
     */
    @GET
    @jakarta.ws.rs.Path("/ocr")
    public Response getOcrRegions(@QueryParam("path") String filePath) {

        if (filePath == null || filePath.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Path parameter is required"))
                    .build();
        }

        if (comparisonService == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "Comparison service not available"))
                    .build();
        }

        // Validate path is within data directory
        Path basePath = Paths.get(config.getDataDirectory()).toAbsolutePath().normalize();
        Path targetPath = Paths.get(filePath).toAbsolutePath().normalize();

        if (!targetPath.startsWith(basePath)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Access denied: Path outside data directory"))
                    .build();
        }

        if (!Files.exists(targetPath)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "File not found"))
                    .build();
        }

        try {
            List<DetectedRegion> regions = comparisonService.getOcrRegions(targetPath);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", filePath);
            result.put("regionCount", regions.size());
            result.put("regions", regions);
            result.put("hasPhiRegions", regions.stream().anyMatch(DetectedRegion::isPhi));

            return Response.ok(result).build();
        } catch (Exception e) {
            log.error("Failed to get OCR regions: {}", e.getMessage(), e);
            return Response.serverError()
                    .entity(Map.of("error", "Failed to get OCR regions: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Get image dimensions for a DICOM file.
     * Useful for frontend to set up OCR overlay dimensions.
     */
    @GET
    @jakarta.ws.rs.Path("/image-info")
    public Response getImageInfo(@QueryParam("path") String filePath) {

        if (filePath == null || filePath.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Path parameter is required"))
                    .build();
        }

        // Validate path is within data directory
        Path basePath = Paths.get(config.getDataDirectory()).toAbsolutePath().normalize();
        Path targetPath = Paths.get(filePath).toAbsolutePath().normalize();

        if (!targetPath.startsWith(basePath)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Access denied: Path outside data directory"))
                    .build();
        }

        if (!Files.exists(targetPath)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "File not found"))
                    .build();
        }

        try {
            org.dcm4che3.io.DicomInputStream dis = new org.dcm4che3.io.DicomInputStream(targetPath.toFile());
            org.dcm4che3.data.Attributes attrs = dis.readDataset();
            dis.close();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", filePath);
            result.put("width", attrs.getInt(org.dcm4che3.data.Tag.Columns, 0));
            result.put("height", attrs.getInt(org.dcm4che3.data.Tag.Rows, 0));
            result.put("frames", attrs.getInt(org.dcm4che3.data.Tag.NumberOfFrames, 1));
            result.put("modality", attrs.getString(org.dcm4che3.data.Tag.Modality, ""));
            result.put("sopClassUid", attrs.getString(org.dcm4che3.data.Tag.SOPClassUID, ""));

            return Response.ok(result).build();
        } catch (Exception e) {
            log.error("Failed to get image info: {}", e.getMessage(), e);
            return Response.serverError()
                    .entity(Map.of("error", "Failed to get image info: " + e.getMessage()))
                    .build();
        }
    }
}
