/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.api;

import io.xnatworks.router.anon.AnonymizationAuditService;
import io.xnatworks.router.anon.AnonymizationAuditService.*;
import io.xnatworks.router.anon.ScriptLibrary;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
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
 * REST API for anonymization audit reports.
 * Provides endpoints to generate and retrieve audit reports comparing
 * original vs anonymized DICOM files and validating conformance to scripts.
 */
@jakarta.ws.rs.Path("/audit")
@Produces(MediaType.APPLICATION_JSON)
public class AuditResource {

    private static final Logger log = LoggerFactory.getLogger(AuditResource.class);

    private final AnonymizationAuditService auditService;
    private final ScriptLibrary scriptLibrary;
    private final Path dataDir;

    // Cache of recent audit reports (in-memory for now)
    private final Map<String, AuditReport> reportCache = new LinkedHashMap<String, AuditReport>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, AuditReport> eldest) {
            return size() > 100; // Keep last 100 reports in memory
        }
    };

    public AuditResource(ScriptLibrary scriptLibrary, Path dataDir) {
        this.scriptLibrary = scriptLibrary;
        this.auditService = new AnonymizationAuditService(scriptLibrary);
        this.dataDir = dataDir;
    }

    /**
     * Generate an audit report comparing original and anonymized directories.
     *
     * POST /audit/report
     * Body: { "originalDir": "/path/to/original", "anonymizedDir": "/path/to/anonymized", "scriptName": "hipaa_standard" }
     */
    @POST
    @jakarta.ws.rs.Path("/report")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response generateReport(Map<String, Object> request) {
        String originalDir = (String) request.get("originalDir");
        String anonymizedDir = (String) request.get("anonymizedDir");
        String scriptName = (String) request.get("scriptName");

        if (originalDir == null || originalDir.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "originalDir is required"))
                    .build();
        }

        if (anonymizedDir == null || anonymizedDir.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "anonymizedDir is required"))
                    .build();
        }

        Path originalPath = Paths.get(originalDir);
        Path anonymizedPath = Paths.get(anonymizedDir);

        if (!Files.exists(originalPath)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Original directory does not exist: " + originalDir))
                    .build();
        }

        if (!Files.exists(anonymizedPath)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Anonymized directory does not exist: " + anonymizedDir))
                    .build();
        }

        try {
            AuditReport report = auditService.generateReport(originalPath, anonymizedPath, scriptName);

            // Cache the report
            reportCache.put(report.getReportId(), report);

            return Response.ok(reportToMap(report, false)).build();
        } catch (Exception e) {
            log.error("Failed to generate audit report: {}", e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to generate report: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Generate audit report for a specific route's storage directories.
     * Compares incoming vs completed directories.
     *
     * POST /audit/route/{routeName}
     * Body: { "scriptName": "hipaa_standard", "studyDir": "study_xxx" }
     */
    @POST
    @jakarta.ws.rs.Path("/route/{routeName}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response generateRouteAudit(
            @PathParam("routeName") String routeName,
            Map<String, Object> request) {

        String scriptName = (String) request.get("scriptName");
        String studyDir = (String) request.get("studyDir");

        Path routeDir = dataDir.resolve(routeName);
        if (!Files.exists(routeDir)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Route not found: " + routeName))
                    .build();
        }

        // Determine original and anonymized directories
        Path originalPath;
        Path anonymizedPath;

        if (studyDir != null && !studyDir.isEmpty()) {
            // Compare specific study in incoming vs completed
            originalPath = routeDir.resolve("incoming").resolve(studyDir);
            anonymizedPath = routeDir.resolve("completed").resolve(studyDir);
        } else {
            // Compare entire incoming vs completed
            originalPath = routeDir.resolve("incoming");
            anonymizedPath = routeDir.resolve("completed");
        }

        if (!Files.exists(originalPath)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Original directory does not exist: " + originalPath))
                    .build();
        }

        if (!Files.exists(anonymizedPath)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Anonymized/completed directory does not exist: " + anonymizedPath))
                    .build();
        }

        try {
            AuditReport report = auditService.generateReport(originalPath, anonymizedPath, scriptName);
            reportCache.put(report.getReportId(), report);
            return Response.ok(reportToMap(report, false)).build();
        } catch (Exception e) {
            log.error("Failed to generate route audit report: {}", e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to generate report: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Get a cached audit report by ID.
     *
     * GET /audit/report/{reportId}
     */
    @GET
    @jakarta.ws.rs.Path("/report/{reportId}")
    public Response getReport(
            @PathParam("reportId") String reportId,
            @QueryParam("full") @DefaultValue("false") boolean full) {

        AuditReport report = reportCache.get(reportId);
        if (report == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Report not found: " + reportId))
                    .build();
        }

        return Response.ok(reportToMap(report, full)).build();
    }

    /**
     * Get file comparison details for a specific file in an audit report.
     *
     * GET /audit/report/{reportId}/file/{fileName}
     */
    @GET
    @jakarta.ws.rs.Path("/report/{reportId}/file/{fileName}")
    public Response getFileComparison(
            @PathParam("reportId") String reportId,
            @PathParam("fileName") String fileName) {

        AuditReport report = reportCache.get(reportId);
        if (report == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Report not found: " + reportId))
                    .build();
        }

        for (FileComparison fc : report.getFileComparisons()) {
            if (fc.getOriginalFile().equals(fileName) || fc.getAnonymizedFile().equals(fileName)) {
                return Response.ok(fileComparisonToMap(fc)).build();
            }
        }

        return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", "File not found in report: " + fileName))
                .build();
    }

    /**
     * Compare a single pair of files directly.
     *
     * POST /audit/compare
     * Body: { "originalFile": "/path/to/original.dcm", "anonymizedFile": "/path/to/anon.dcm", "scriptName": "hipaa_standard" }
     */
    @POST
    @jakarta.ws.rs.Path("/compare")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response compareFiles(Map<String, Object> request) {
        String originalFile = (String) request.get("originalFile");
        String anonymizedFile = (String) request.get("anonymizedFile");
        String scriptName = (String) request.get("scriptName");

        if (originalFile == null || anonymizedFile == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "originalFile and anonymizedFile are required"))
                    .build();
        }

        Path originalPath = Paths.get(originalFile);
        Path anonymizedPath = Paths.get(anonymizedFile);

        if (!Files.exists(originalPath)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Original file does not exist"))
                    .build();
        }

        if (!Files.exists(anonymizedPath)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Anonymized file does not exist"))
                    .build();
        }

        try {
            FileComparison comparison = auditService.compareFiles(originalPath, anonymizedPath, scriptName);
            return Response.ok(fileComparisonToMap(comparison)).build();
        } catch (Exception e) {
            log.error("Failed to compare files: {}", e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to compare files: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * List recent audit reports.
     *
     * GET /audit/reports
     */
    @GET
    @jakarta.ws.rs.Path("/reports")
    public Response listReports() {
        List<Map<String, Object>> reports = new ArrayList<>();

        for (AuditReport report : reportCache.values()) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("reportId", report.getReportId());
            summary.put("generatedAt", report.getGeneratedAt() != null ? report.getGeneratedAt().toString() : null);
            summary.put("scriptName", report.getScriptName());
            summary.put("matchedFiles", report.getMatchedFiles());
            summary.put("fullyConformant", report.isFullyConformant());
            summary.put("nonConformantFiles", report.getNonConformantFiles());
            reports.add(summary);
        }

        return Response.ok(reports).build();
    }

    /**
     * Clear the report cache.
     *
     * DELETE /audit/reports
     */
    @DELETE
    @jakarta.ws.rs.Path("/reports")
    public Response clearReports() {
        int count = reportCache.size();
        reportCache.clear();
        return Response.ok(Map.of("message", "Cleared " + count + " reports")).build();
    }

    // Helper methods to convert objects to maps for JSON serialization

    private Map<String, Object> reportToMap(AuditReport report, boolean includeFullDetails) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("reportId", report.getReportId());
        map.put("generatedAt", report.getGeneratedAt() != null ? report.getGeneratedAt().toString() : null);
        map.put("originalDirectory", report.getOriginalDirectory());
        map.put("anonymizedDirectory", report.getAnonymizedDirectory());
        map.put("scriptName", report.getScriptName());

        // Summary statistics
        map.put("totalFilesOriginal", report.getTotalFilesOriginal());
        map.put("totalFilesAnonymized", report.getTotalFilesAnonymized());
        map.put("matchedFiles", report.getMatchedFiles());
        map.put("totalChanges", report.getTotalChanges());
        map.put("phiFieldsModified", report.getPhiFieldsModified());

        // Conformance
        map.put("fullyConformant", report.isFullyConformant());
        map.put("nonConformantFiles", report.getNonConformantFiles());
        map.put("errors", report.getErrors());

        // Tag summary (always included)
        List<Map<String, Object>> tagSummary = new ArrayList<>();
        for (TagSummary ts : report.getTagSummary().values()) {
            Map<String, Object> tag = new LinkedHashMap<>();
            tag.put("tagHex", ts.getTagHex());
            tag.put("tagName", ts.getTagName());
            tag.put("changeCount", ts.getChangeCount());
            tagSummary.add(tag);
        }
        map.put("tagSummary", tagSummary);

        // File comparisons (only if full details requested)
        if (includeFullDetails) {
            map.put("scriptContent", report.getScriptContent());

            List<Map<String, Object>> fileComparisons = new ArrayList<>();
            for (FileComparison fc : report.getFileComparisons()) {
                fileComparisons.add(fileComparisonToMap(fc));
            }
            map.put("fileComparisons", fileComparisons);
        } else {
            // Just include summary info about files
            List<Map<String, Object>> fileSummaries = new ArrayList<>();
            for (FileComparison fc : report.getFileComparisons()) {
                Map<String, Object> fileSummary = new LinkedHashMap<>();
                fileSummary.put("originalFile", fc.getOriginalFile());
                fileSummary.put("anonymizedFile", fc.getAnonymizedFile());
                fileSummary.put("changeCount", fc.getChanges().size());
                fileSummary.put("conformanceIssueCount", fc.getConformanceIssues().size());
                fileSummary.put("residualPhiWarningCount", fc.getResidualPhiWarnings().size());
                fileSummaries.add(fileSummary);
            }
            map.put("fileSummaries", fileSummaries);
        }

        return map;
    }

    private Map<String, Object> fileComparisonToMap(FileComparison fc) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("originalFile", fc.getOriginalFile());
        map.put("anonymizedFile", fc.getAnonymizedFile());
        map.put("conformanceIssues", fc.getConformanceIssues());
        map.put("residualPhiWarnings", fc.getResidualPhiWarnings());

        // Changes
        List<Map<String, Object>> changes = new ArrayList<>();
        for (TagChange change : fc.getChanges()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("tagHex", change.getTagHex());
            c.put("tagName", change.getTagName());
            c.put("originalValue", change.getOriginalValue());
            c.put("anonymizedValue", change.getAnonymizedValue());
            c.put("action", change.getAction());
            c.put("phi", change.isPhi());
            changes.add(c);
        }
        map.put("changes", changes);

        return map;
    }
}
