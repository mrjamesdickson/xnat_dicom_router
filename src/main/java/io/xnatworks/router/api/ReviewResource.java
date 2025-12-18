/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.api;

import io.xnatworks.router.anon.AnonymizationAuditService;
import io.xnatworks.router.archive.ArchiveManager;
import io.xnatworks.router.review.ReviewManager;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.*;

/**
 * REST API for the review workflow.
 *
 * Endpoints:
 * - GET  /review/pending                - List pending reviews
 * - GET  /review/pending/{aeTitle}      - List pending reviews for an AE title
 * - GET  /review/{reviewId}             - Get review details
 * - GET  /review/{reviewId}/diff        - Get anonymization diff
 * - POST /review/{reviewId}/approve     - Approve for sending
 * - POST /review/{reviewId}/reject      - Reject study
 * - GET  /review/rejected/{aeTitle}     - List rejected studies
 * - GET  /review/statistics             - Get review statistics
 */
@Path("/review")
@Produces(MediaType.APPLICATION_JSON)
public class ReviewResource {

    private final ReviewManager reviewManager;
    private final ArchiveManager archiveManager;

    public ReviewResource(ReviewManager reviewManager, ArchiveManager archiveManager) {
        this.reviewManager = reviewManager;
        this.archiveManager = archiveManager;
    }

    /**
     * List all pending reviews across all routes.
     */
    @GET
    @Path("/pending")
    public Response listAllPendingReviews(
            @QueryParam("limit") @DefaultValue("100") int limit) {

        if (reviewManager == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "Review manager not available"))
                    .build();
        }

        List<ReviewManager.ReviewMetadata> reviews = reviewManager.getAllPendingReviews();
        if (reviews.size() > limit) {
            reviews = reviews.subList(0, limit);
        }

        List<Map<String, Object>> reviewList = new ArrayList<>();
        for (ReviewManager.ReviewMetadata metadata : reviews) {
            reviewList.add(reviewMetadataToMap(metadata));
        }

        return Response.ok(Map.of(
                "reviews", reviewList,
                "count", reviewList.size()
        )).build();
    }

    /**
     * List pending reviews for a specific AE title.
     */
    @GET
    @Path("/pending/{aeTitle}")
    public Response listPendingReviews(@PathParam("aeTitle") String aeTitle) {
        if (reviewManager == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "Review manager not available"))
                    .build();
        }

        List<ReviewManager.ReviewMetadata> reviews = reviewManager.getPendingReviews(aeTitle);

        List<Map<String, Object>> reviewList = new ArrayList<>();
        for (ReviewManager.ReviewMetadata metadata : reviews) {
            reviewList.add(reviewMetadataToMap(metadata));
        }

        return Response.ok(Map.of(
                "reviews", reviewList,
                "count", reviewList.size(),
                "aeTitle", aeTitle
        )).build();
    }

    /**
     * Get a specific review by ID.
     */
    @GET
    @Path("/{reviewId}")
    public Response getReview(@PathParam("reviewId") String reviewId) {
        if (reviewManager == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "Review manager not available"))
                    .build();
        }

        ReviewManager.ReviewMetadata metadata = reviewManager.getReview(reviewId);
        if (metadata == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Review not found: " + reviewId))
                    .build();
        }

        Map<String, Object> result = reviewMetadataToMap(metadata);

        // Include additional archive information
        if (archiveManager != null) {
            ArchiveManager.ArchivedStudy study = archiveManager.getArchivedStudy(
                    metadata.getAeTitle(), metadata.getStudyUid());
            if (study != null) {
                result.put("hasOriginal", study.getOriginalPath() != null);
                result.put("hasAnonymized", study.getAnonymizedPath() != null);
                result.put("originalFileCount", study.getOriginalFiles() != null ? study.getOriginalFiles().size() : 0);
                result.put("anonymizedFileCount", study.getAnonymizedFiles() != null ? study.getAnonymizedFiles().size() : 0);
            }
        }

        return Response.ok(result).build();
    }

    /**
     * Get the anonymization diff/audit report for a review.
     */
    @GET
    @Path("/{reviewId}/diff")
    public Response getReviewDiff(@PathParam("reviewId") String reviewId) {
        if (reviewManager == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "Review manager not available"))
                    .build();
        }

        try {
            AnonymizationAuditService.AuditReport report = reviewManager.getReviewDiff(reviewId);
            if (report == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Audit report not found for review: " + reviewId))
                        .build();
            }

            // Convert audit report to map
            Map<String, Object> result = auditReportToMap(report);
            result.put("reviewId", reviewId);

            return Response.ok(result).build();

        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to load audit report: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Approve a review for sending.
     */
    @POST
    @Path("/{reviewId}/approve")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response approveReview(
            @PathParam("reviewId") String reviewId,
            Map<String, String> body) {

        if (reviewManager == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "Review manager not available"))
                    .build();
        }

        String userId = body != null ? body.get("userId") : null;
        String notes = body != null ? body.get("notes") : null;

        if (userId == null || userId.isEmpty()) {
            userId = "admin"; // Default user if not provided
        }

        try {
            boolean success = reviewManager.approveReview(reviewId, userId, notes);
            if (!success) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Failed to approve review"))
                        .build();
            }

            return Response.ok(Map.of(
                    "message", "Review approved",
                    "reviewId", reviewId,
                    "approvedBy", userId
            )).build();

        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to approve review: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Reject a review.
     */
    @POST
    @Path("/{reviewId}/reject")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response rejectReview(
            @PathParam("reviewId") String reviewId,
            Map<String, String> body) {

        if (reviewManager == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "Review manager not available"))
                    .build();
        }

        String userId = body != null ? body.get("userId") : null;
        String reason = body != null ? body.get("reason") : null;

        if (userId == null || userId.isEmpty()) {
            userId = "admin"; // Default user if not provided
        }

        if (reason == null || reason.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Rejection reason is required"))
                    .build();
        }

        try {
            boolean success = reviewManager.rejectReview(reviewId, userId, reason);
            if (!success) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Failed to reject review"))
                        .build();
            }

            return Response.ok(Map.of(
                    "message", "Review rejected",
                    "reviewId", reviewId,
                    "rejectedBy", userId,
                    "reason", reason
            )).build();

        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to reject review: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Get rejected studies for an AE title.
     */
    @GET
    @Path("/rejected/{aeTitle}")
    public Response getRejectedStudies(
            @PathParam("aeTitle") String aeTitle,
            @QueryParam("limit") @DefaultValue("50") int limit) {

        if (reviewManager == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "Review manager not available"))
                    .build();
        }

        List<ReviewManager.RejectionMetadata> rejected = reviewManager.getRejectedStudies(aeTitle, limit);

        List<Map<String, Object>> rejectedList = new ArrayList<>();
        for (ReviewManager.RejectionMetadata metadata : rejected) {
            rejectedList.add(rejectionMetadataToMap(metadata));
        }

        return Response.ok(Map.of(
                "rejected", rejectedList,
                "count", rejectedList.size(),
                "aeTitle", aeTitle
        )).build();
    }

    /**
     * Get review statistics.
     */
    @GET
    @Path("/statistics")
    public Response getReviewStatistics(@QueryParam("aeTitle") String aeTitle) {
        if (reviewManager == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "Review manager not available"))
                    .build();
        }

        Map<String, Object> stats = new LinkedHashMap<>();

        if (aeTitle != null && !aeTitle.isEmpty()) {
            // Statistics for specific AE title
            int pendingCount = reviewManager.getPendingReviewCount(aeTitle);
            int rejectedCount = reviewManager.getRejectedStudies(aeTitle, 1000).size();

            stats.put("aeTitle", aeTitle);
            stats.put("pendingReviews", pendingCount);
            stats.put("rejectedStudies", rejectedCount);
            stats.put("requiresReview", reviewManager.isReviewRequired(aeTitle));
        } else {
            // Global statistics
            List<ReviewManager.ReviewMetadata> allPending = reviewManager.getAllPendingReviews();
            stats.put("totalPendingReviews", allPending.size());

            // Group by AE title
            Map<String, Integer> byAeTitle = new LinkedHashMap<>();
            for (ReviewManager.ReviewMetadata review : allPending) {
                byAeTitle.merge(review.getAeTitle(), 1, Integer::sum);
            }
            stats.put("pendingByAeTitle", byAeTitle);
        }

        return Response.ok(stats).build();
    }

    // Helper methods

    private Map<String, Object> reviewMetadataToMap(ReviewManager.ReviewMetadata metadata) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("reviewId", metadata.getReviewId());
        map.put("studyUid", metadata.getStudyUid());
        map.put("aeTitle", metadata.getAeTitle());
        map.put("archivePath", metadata.getArchivePath());
        map.put("submittedAt", metadata.getSubmittedAt() != null ? metadata.getSubmittedAt().toString() : null);
        map.put("status", metadata.getStatus() != null ? metadata.getStatus().name() : null);
        map.put("scriptUsed", metadata.getScriptUsed());
        map.put("phiFieldsModified", metadata.getPhiFieldsModified());
        map.put("warnings", metadata.getWarnings());
        map.put("reviewedAt", metadata.getReviewedAt() != null ? metadata.getReviewedAt().toString() : null);
        map.put("reviewedBy", metadata.getReviewedBy());
        map.put("reviewNotes", metadata.getReviewNotes());
        return map;
    }

    private Map<String, Object> rejectionMetadataToMap(ReviewManager.RejectionMetadata metadata) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("studyUid", metadata.getStudyUid());
        map.put("aeTitle", metadata.getAeTitle());
        map.put("reviewId", metadata.getReviewId());
        map.put("rejectedAt", metadata.getRejectedAt() != null ? metadata.getRejectedAt().toString() : null);
        map.put("rejectedBy", metadata.getRejectedBy());
        map.put("rejectionReason", metadata.getRejectionReason());
        map.put("originalSubmittedAt", metadata.getOriginalSubmittedAt() != null ?
                metadata.getOriginalSubmittedAt().toString() : null);
        map.put("scriptUsed", metadata.getScriptUsed());
        map.put("archivePath", metadata.getArchivePath());
        return map;
    }

    private Map<String, Object> auditReportToMap(AnonymizationAuditService.AuditReport report) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("reportId", report.getReportId());
        map.put("generatedAt", report.getGeneratedAt() != null ? report.getGeneratedAt().toString() : null);
        map.put("scriptName", report.getScriptName());
        map.put("originalFileCount", report.getTotalFilesOriginal());
        map.put("anonymizedFileCount", report.getTotalFilesAnonymized());
        map.put("matchedFiles", report.getMatchedFiles());
        map.put("phiFieldsModified", report.getPhiFieldsModified());
        map.put("nonConformantFiles", report.getNonConformantFiles());
        map.put("totalChanges", report.getTotalChanges());
        map.put("fullyConformant", report.isFullyConformant());

        // Include tag summary
        Map<String, Object> tagSummary = new LinkedHashMap<>();
        if (report.getTagSummary() != null) {
            for (Map.Entry<String, AnonymizationAuditService.TagSummary> entry : report.getTagSummary().entrySet()) {
                AnonymizationAuditService.TagSummary ts = entry.getValue();
                Map<String, Object> summaryMap = new LinkedHashMap<>();
                summaryMap.put("tagName", ts.getTagName());
                summaryMap.put("changeCount", ts.getChangeCount());
                tagSummary.put(entry.getKey(), summaryMap);
            }
        }
        map.put("tagSummary", tagSummary);

        // Include file comparisons (limited for API response size)
        List<Map<String, Object>> fileComparisons = new ArrayList<>();
        if (report.getFileComparisons() != null) {
            int limit = Math.min(10, report.getFileComparisons().size()); // Limit to first 10 for API
            for (int i = 0; i < limit; i++) {
                AnonymizationAuditService.FileComparison fc = report.getFileComparisons().get(i);
                Map<String, Object> fcMap = new LinkedHashMap<>();
                fcMap.put("originalFile", fc.getOriginalFile());
                fcMap.put("anonymizedFile", fc.getAnonymizedFile());
                fcMap.put("changesCount", fc.getChanges() != null ? fc.getChanges().size() : 0);
                fcMap.put("conformanceIssues", fc.getConformanceIssues());
                fcMap.put("residualPhiWarnings", fc.getResidualPhiWarnings());

                // Include sample tag changes for first file
                if (i == 0 && fc.getChanges() != null && !fc.getChanges().isEmpty()) {
                    List<Map<String, Object>> changes = new ArrayList<>();
                    int changeLimit = Math.min(20, fc.getChanges().size());
                    for (int j = 0; j < changeLimit; j++) {
                        AnonymizationAuditService.TagChange change = fc.getChanges().get(j);
                        Map<String, Object> changeMap = new LinkedHashMap<>();
                        changeMap.put("tagHex", change.getTagHex());
                        changeMap.put("tagName", change.getTagName());
                        changeMap.put("originalValue", change.getOriginalValue());
                        changeMap.put("anonymizedValue", change.getAnonymizedValue());
                        changeMap.put("action", change.getAction());
                        changeMap.put("phi", change.isPhi());
                        changes.add(changeMap);
                    }
                    fcMap.put("sampleChanges", changes);
                }

                fileComparisons.add(fcMap);
            }
        }
        map.put("fileComparisons", fileComparisons);
        map.put("totalFileComparisons", report.getFileComparisons() != null ? report.getFileComparisons().size() : 0);

        // Include errors
        map.put("errors", report.getErrors());

        return map;
    }
}
