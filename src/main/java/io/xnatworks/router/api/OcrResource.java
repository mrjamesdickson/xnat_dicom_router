/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */
package io.xnatworks.router.api;

import io.xnatworks.router.config.AppConfig;
import io.xnatworks.router.ocr.DetectedRegion;
import io.xnatworks.router.ocr.DicomOcrProcessor;
import io.xnatworks.router.ocr.OcrService;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for OCR-based PHI detection in DICOM images.
 */
@Path("/ocr")
@Produces(MediaType.APPLICATION_JSON)
public class OcrResource {
    private static final Logger log = LoggerFactory.getLogger(OcrResource.class);

    private final AppConfig config;
    private final OcrService ocrService;
    private final DicomOcrProcessor processor;

    public OcrResource(AppConfig config) {
        this.config = config;
        this.ocrService = new OcrService();
        this.processor = new DicomOcrProcessor(ocrService, 2, 60.0f);
    }

    /**
     * Get OCR service status.
     */
    @GET
    @Path("/status")
    public Response getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("available", ocrService.isAvailable());
        status.put("tessDataPath", ocrService.getTessDataPath());

        if (!ocrService.isAvailable()) {
            status.put("message", "Tesseract not available. Ensure tessdata directory exists with eng.traineddata");
        } else {
            status.put("message", "OCR service ready");
        }

        return Response.ok(status).build();
    }

    /**
     * Scan a DICOM file for PHI in pixel data.
     */
    @POST
    @Path("/scan")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response scanFile(ScanRequest request) {
        if (!ocrService.isAvailable()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(Map.of("error", "OCR service not available"))
                .build();
        }

        if (request.path == null || request.path.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "Path is required"))
                .build();
        }

        File file = new File(request.path);
        if (!file.exists()) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", "File not found: " + request.path))
                .build();
        }

        try {
            DicomOcrProcessor.OcrResult result = processor.processDicomFile(file);

            Map<String, Object> response = new HashMap<>();
            response.put("sourceName", result.getSourceName());
            response.put("imageWidth", result.getImageWidth());
            response.put("imageHeight", result.getImageHeight());
            response.put("totalRegions", result.getDetectedRegions().size());
            response.put("phiRegionCount", result.getPhiRegionCount());
            response.put("regions", result.getDetectedRegions());

            if (result.hasError()) {
                response.put("error", result.getError());
            }

            if (result.getAlterPixelScript() != null) {
                response.put("alterPixelScript", result.getAlterPixelScript());
            }

            return Response.ok(response).build();

        } catch (IOException e) {
            log.error("Failed to scan file: {}", e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    /**
     * Scan a directory for PHI in DICOM images.
     */
    @POST
    @Path("/scan-directory")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response scanDirectory(ScanRequest request) {
        if (!ocrService.isAvailable()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(Map.of("error", "OCR service not available"))
                .build();
        }

        if (request.path == null || request.path.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "Path is required"))
                .build();
        }

        File dir = new File(request.path);
        if (!dir.exists() || !dir.isDirectory()) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", "Directory not found: " + request.path))
                .build();
        }

        // Find DICOM files
        File[] files = dir.listFiles((d, name) ->
            name.toLowerCase().endsWith(".dcm") || !name.contains("."));

        if (files == null || files.length == 0) {
            return Response.ok(Map.of(
                "path", request.path,
                "filesScanned", 0,
                "totalPhiRegions", 0,
                "results", List.of()
            )).build();
        }

        // Limit to first N files for performance
        int maxFiles = request.maxFiles > 0 ? request.maxFiles : 10;
        int totalPhiRegions = 0;
        java.util.List<Map<String, Object>> results = new java.util.ArrayList<>();

        for (int i = 0; i < Math.min(files.length, maxFiles); i++) {
            File file = files[i];
            try {
                DicomOcrProcessor.OcrResult result = processor.processDicomFile(file);

                Map<String, Object> fileResult = new HashMap<>();
                fileResult.put("file", file.getName());
                fileResult.put("phiRegionCount", result.getPhiRegionCount());

                if (result.hasPhiDetected()) {
                    fileResult.put("regions", result.getDetectedRegions().stream()
                        .filter(DetectedRegion::isPhi)
                        .toList());
                    fileResult.put("script", result.getAlterPixelScript());
                    totalPhiRegions += result.getPhiRegionCount();
                }

                if (result.hasError()) {
                    fileResult.put("error", result.getError());
                }

                results.add(fileResult);

            } catch (IOException e) {
                results.add(Map.of(
                    "file", file.getName(),
                    "error", e.getMessage()
                ));
            }
        }

        return Response.ok(Map.of(
            "path", request.path,
            "filesScanned", results.size(),
            "totalFiles", files.length,
            "totalPhiRegions", totalPhiRegions,
            "results", results
        )).build();
    }

    /**
     * Apply redaction to a DICOM file.
     */
    @POST
    @Path("/redact")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response redactFile(RedactRequest request) {
        if (!ocrService.isAvailable()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(Map.of("error", "OCR service not available"))
                .build();
        }

        if (request.inputPath == null || request.outputPath == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "inputPath and outputPath are required"))
                .build();
        }

        File inputFile = new File(request.inputPath);
        if (!inputFile.exists()) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", "Input file not found"))
                .build();
        }

        File outputFile = new File(request.outputPath);

        try {
            // First scan for PHI
            DicomOcrProcessor.OcrResult scanResult = processor.processDicomFile(inputFile);

            if (!scanResult.hasPhiDetected()) {
                return Response.ok(Map.of(
                    "success", false,
                    "message", "No PHI detected, no redaction needed"
                )).build();
            }

            // Apply redaction
            boolean success = processor.applyRedaction(
                inputFile, outputFile, scanResult.getDetectedRegions()
            );

            return Response.ok(Map.of(
                "success", success,
                "inputFile", inputFile.getName(),
                "outputFile", outputFile.getAbsolutePath(),
                "regionsRedacted", scanResult.getPhiRegionCount()
            )).build();

        } catch (IOException e) {
            log.error("Redaction failed: {}", e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    /**
     * Scan a regular image file (PNG, JPG) for PHI - useful for testing.
     */
    @POST
    @Path("/scan-image")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response scanImage(ScanRequest request) {
        if (!ocrService.isAvailable()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(Map.of("error", "OCR service not available"))
                .build();
        }

        if (request.path == null || request.path.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "Path is required"))
                .build();
        }

        File file = new File(request.path);
        if (!file.exists()) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", "File not found: " + request.path))
                .build();
        }

        try {
            List<DetectedRegion> regions = ocrService.detectTextInFile(file);

            Map<String, Object> response = new HashMap<>();
            response.put("sourceName", file.getName());
            response.put("totalRegions", regions.size());
            response.put("phiRegionCount", regions.stream().filter(DetectedRegion::isPhi).count());
            response.put("regions", regions);

            return Response.ok(response).build();

        } catch (IOException e) {
            log.error("Failed to scan image: {}", e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    /**
     * Generate AlterPixel script for a file without applying redaction.
     */
    @POST
    @Path("/generate-script")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public Response generateScript(ScanRequest request) {
        if (!ocrService.isAvailable()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity("OCR service not available")
                .build();
        }

        if (request.path == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity("Path is required")
                .build();
        }

        File file = new File(request.path);
        if (!file.exists()) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity("File not found")
                .build();
        }

        try {
            DicomOcrProcessor.OcrResult result = processor.processDicomFile(file);

            if (result.hasError()) {
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("// Error: " + result.getError())
                    .build();
            }

            String script = result.getAlterPixelScript();
            if (script == null || script.isEmpty()) {
                script = "// No PHI regions detected";
            }

            return Response.ok(script).build();

        } catch (IOException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("// Error: " + e.getMessage())
                .build();
        }
    }

    // Request DTOs

    public static class ScanRequest {
        public String path;
        public int maxFiles = 10;
        public float confidenceThreshold = 60.0f;
    }

    public static class RedactRequest {
        public String inputPath;
        public String outputPath;
        public int padding = 2;
    }
}
