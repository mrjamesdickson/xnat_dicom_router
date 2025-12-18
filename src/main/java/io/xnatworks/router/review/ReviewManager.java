/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.review;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.xnatworks.router.anon.AnonymizationAuditService;
import io.xnatworks.router.archive.ArchiveManager;
import io.xnatworks.router.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages the review workflow for anonymized studies.
 *
 * When a route has `require_review: true` and includes anonymization:
 * 1. Study is archived (original + anonymized + audit report)
 * 2. Study is submitted for review (creates entry in pending_review/)
 * 3. Reviewer can approve or reject via REST API
 * 4. On approval: Study proceeds to forwarding
 * 5. On rejection: Study is moved to rejected/
 *
 * Pending review structure:
 * {baseDir}/{aeTitle}/pending_review/study_{uid}/
 *   └── review_metadata.json
 *
 * Rejected structure:
 * {baseDir}/{aeTitle}/rejected/study_{uid}/
 *   └── rejection_metadata.json
 */
public class ReviewManager {
    private static final Logger log = LoggerFactory.getLogger(ReviewManager.class);

    private static final String PENDING_REVIEW_DIR = "pending_review";
    private static final String REJECTED_DIR = "rejected";
    private static final String REVIEW_METADATA_FILE = "review_metadata.json";
    private static final String REJECTION_METADATA_FILE = "rejection_metadata.json";

    private final Path baseDir;
    private final AppConfig config;
    private final ArchiveManager archiveManager;
    private final ObjectMapper objectMapper;

    // Callback interface for when a review is approved
    private ReviewApprovalCallback approvalCallback;

    public ReviewManager(Path baseDir, AppConfig config, ArchiveManager archiveManager) {
        this.baseDir = baseDir;
        this.config = config;
        this.archiveManager = archiveManager;

        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Set callback to be invoked when a review is approved.
     * This allows integration with the forwarding pipeline.
     */
    public void setApprovalCallback(ReviewApprovalCallback callback) {
        this.approvalCallback = callback;
    }

    /**
     * Submit a study for review.
     * Creates a pending_review entry with metadata.
     *
     * @param aeTitle      The route AE title
     * @param studyUid     The study UID
     * @param scriptName   The anonymization script used
     * @param phiFieldsModified Number of PHI fields modified
     * @param warnings     Any warnings from anonymization
     * @return The review ID (UUID)
     */
    public String submitForReview(String aeTitle, String studyUid, String scriptName,
                                   int phiFieldsModified, List<String> warnings) throws IOException {
        log.info("[{}] Submitting study {} for review (script: {}, {} PHI fields modified)",
                aeTitle, studyUid, scriptName, phiFieldsModified);

        String reviewId = UUID.randomUUID().toString();

        // Create review metadata
        ReviewMetadata metadata = new ReviewMetadata();
        metadata.setReviewId(reviewId);
        metadata.setStudyUid(studyUid);
        metadata.setAeTitle(aeTitle);
        metadata.setSubmittedAt(LocalDateTime.now());
        metadata.setStatus(ReviewStatus.PENDING_REVIEW);
        metadata.setScriptUsed(scriptName);
        metadata.setPhiFieldsModified(phiFieldsModified);
        metadata.setWarnings(warnings != null ? warnings : Collections.emptyList());

        // Get archive path if available
        ArchiveManager.ArchivedStudy archivedStudy = archiveManager.getArchivedStudy(aeTitle, studyUid);
        if (archivedStudy != null && archivedStudy.getArchivePath() != null) {
            metadata.setArchivePath(archivedStudy.getArchivePath().toString());
        }

        // Create pending_review directory
        Path reviewDir = getPendingReviewDir(aeTitle, studyUid);
        Files.createDirectories(reviewDir);

        // Save metadata
        Path metadataFile = reviewDir.resolve(REVIEW_METADATA_FILE);
        objectMapper.writeValue(metadataFile.toFile(), metadata);

        log.info("[{}] Study {} submitted for review with ID {}", aeTitle, studyUid, reviewId);

        return reviewId;
    }

    /**
     * Get all pending reviews for an AE title.
     */
    public List<ReviewMetadata> getPendingReviews(String aeTitle) {
        List<ReviewMetadata> reviews = new ArrayList<>();

        try {
            Path pendingDir = baseDir.resolve(aeTitle).resolve(PENDING_REVIEW_DIR);
            if (!Files.exists(pendingDir)) {
                return reviews;
            }

            try (Stream<Path> studyDirs = Files.list(pendingDir)) {
                studyDirs.filter(Files::isDirectory)
                         .filter(p -> p.getFileName().toString().startsWith("study_"))
                         .forEach(studyDir -> {
                             try {
                                 ReviewMetadata metadata = loadReviewMetadata(studyDir);
                                 if (metadata != null) {
                                     reviews.add(metadata);
                                 }
                             } catch (Exception e) {
                                 log.warn("Failed to load review metadata from {}: {}", studyDir, e.getMessage());
                             }
                         });
            }

            // Sort by submission time (newest first)
            reviews.sort((a, b) -> {
                if (a.getSubmittedAt() == null) return 1;
                if (b.getSubmittedAt() == null) return -1;
                return b.getSubmittedAt().compareTo(a.getSubmittedAt());
            });

        } catch (IOException e) {
            log.error("[{}] Failed to list pending reviews: {}", aeTitle, e.getMessage(), e);
        }

        return reviews;
    }

    /**
     * Get all pending reviews across all routes.
     */
    public List<ReviewMetadata> getAllPendingReviews() {
        List<ReviewMetadata> allReviews = new ArrayList<>();

        for (AppConfig.RouteConfig route : config.getRoutes()) {
            if (route.isEnabled() && route.isRequireReview()) {
                allReviews.addAll(getPendingReviews(route.getAeTitle()));
            }
        }

        // Sort by submission time (newest first)
        allReviews.sort((a, b) -> {
            if (a.getSubmittedAt() == null) return 1;
            if (b.getSubmittedAt() == null) return -1;
            return b.getSubmittedAt().compareTo(a.getSubmittedAt());
        });

        return allReviews;
    }

    /**
     * Get a specific review by ID.
     */
    public ReviewMetadata getReview(String reviewId) {
        // Search through all pending reviews
        for (AppConfig.RouteConfig route : config.getRoutes()) {
            for (ReviewMetadata metadata : getPendingReviews(route.getAeTitle())) {
                if (reviewId.equals(metadata.getReviewId())) {
                    return metadata;
                }
            }
        }
        return null;
    }

    /**
     * Get review by study UID.
     */
    public ReviewMetadata getReviewByStudyUid(String aeTitle, String studyUid) {
        Path reviewDir = getPendingReviewDir(aeTitle, studyUid);
        if (!Files.exists(reviewDir)) {
            return null;
        }
        return loadReviewMetadata(reviewDir);
    }

    /**
     * Get the anonymization diff/audit report for a review.
     */
    public AnonymizationAuditService.AuditReport getReviewDiff(String reviewId) throws IOException {
        ReviewMetadata metadata = getReview(reviewId);
        if (metadata == null) {
            return null;
        }

        ArchiveManager.ArchivedStudy study = archiveManager.getArchivedStudy(
                metadata.getAeTitle(), metadata.getStudyUid());

        if (study == null || study.getAuditReport() == null) {
            return null;
        }

        return study.getAuditReport();
    }

    /**
     * Approve a review and trigger forwarding.
     *
     * @param reviewId The review ID
     * @param userId   User who approved
     * @param notes    Optional approval notes
     * @return true if approved successfully
     */
    public boolean approveReview(String reviewId, String userId, String notes) throws IOException {
        ReviewMetadata metadata = getReview(reviewId);
        if (metadata == null) {
            log.error("Review not found: {}", reviewId);
            return false;
        }

        if (metadata.getStatus() != ReviewStatus.PENDING_REVIEW) {
            log.error("Review {} is not pending (status: {})", reviewId, metadata.getStatus());
            return false;
        }

        log.info("[{}] Approving review {} for study {} (user: {})",
                metadata.getAeTitle(), reviewId, metadata.getStudyUid(), userId);

        // Update metadata
        metadata.setStatus(ReviewStatus.APPROVED);
        metadata.setReviewedAt(LocalDateTime.now());
        metadata.setReviewedBy(userId);
        metadata.setReviewNotes(notes);

        // Save updated metadata
        Path reviewDir = getPendingReviewDir(metadata.getAeTitle(), metadata.getStudyUid());
        Path metadataFile = reviewDir.resolve(REVIEW_METADATA_FILE);
        objectMapper.writeValue(metadataFile.toFile(), metadata);

        // Trigger forwarding callback if set
        if (approvalCallback != null) {
            try {
                ArchiveManager.ArchivedStudy study = archiveManager.getArchivedStudy(
                        metadata.getAeTitle(), metadata.getStudyUid());
                if (study != null) {
                    approvalCallback.onApproval(metadata, study);
                }
            } catch (Exception e) {
                log.error("[{}] Failed to trigger approval callback for {}: {}",
                        metadata.getAeTitle(), metadata.getStudyUid(), e.getMessage(), e);
            }
        }

        // Remove from pending_review
        deleteDirectory(reviewDir);

        log.info("[{}] Review {} approved and removed from pending queue", metadata.getAeTitle(), reviewId);

        return true;
    }

    /**
     * Reject a review.
     *
     * @param reviewId The review ID
     * @param userId   User who rejected
     * @param reason   Reason for rejection
     * @return true if rejected successfully
     */
    public boolean rejectReview(String reviewId, String userId, String reason) throws IOException {
        ReviewMetadata metadata = getReview(reviewId);
        if (metadata == null) {
            log.error("Review not found: {}", reviewId);
            return false;
        }

        if (metadata.getStatus() != ReviewStatus.PENDING_REVIEW) {
            log.error("Review {} is not pending (status: {})", reviewId, metadata.getStatus());
            return false;
        }

        log.info("[{}] Rejecting review {} for study {} (user: {}, reason: {})",
                metadata.getAeTitle(), reviewId, metadata.getStudyUid(), userId, reason);

        String aeTitle = metadata.getAeTitle();
        String studyUid = metadata.getStudyUid();

        // Update metadata
        metadata.setStatus(ReviewStatus.REJECTED);
        metadata.setReviewedAt(LocalDateTime.now());
        metadata.setReviewedBy(userId);
        metadata.setRejectionReason(reason);

        // Move study to rejected/
        Path rejectedDir = getRejectedDir(aeTitle, studyUid);
        Files.createDirectories(rejectedDir);

        // Create rejection metadata
        RejectionMetadata rejection = new RejectionMetadata();
        rejection.setStudyUid(studyUid);
        rejection.setAeTitle(aeTitle);
        rejection.setReviewId(reviewId);
        rejection.setRejectedAt(LocalDateTime.now());
        rejection.setRejectedBy(userId);
        rejection.setRejectionReason(reason);
        rejection.setOriginalSubmittedAt(metadata.getSubmittedAt());
        rejection.setScriptUsed(metadata.getScriptUsed());
        rejection.setArchivePath(metadata.getArchivePath());

        // Save rejection metadata
        Path rejectionFile = rejectedDir.resolve(REJECTION_METADATA_FILE);
        objectMapper.writeValue(rejectionFile.toFile(), rejection);

        // Remove from pending_review
        Path reviewDir = getPendingReviewDir(aeTitle, studyUid);
        deleteDirectory(reviewDir);

        log.info("[{}] Review {} rejected and study moved to rejected folder", aeTitle, reviewId);

        return true;
    }

    /**
     * Get rejected studies for an AE title.
     */
    public List<RejectionMetadata> getRejectedStudies(String aeTitle, int limit) {
        List<RejectionMetadata> rejected = new ArrayList<>();

        try {
            Path rejectedDir = baseDir.resolve(aeTitle).resolve(REJECTED_DIR);
            if (!Files.exists(rejectedDir)) {
                return rejected;
            }

            try (Stream<Path> studyDirs = Files.list(rejectedDir)) {
                studyDirs.filter(Files::isDirectory)
                         .filter(p -> p.getFileName().toString().startsWith("study_"))
                         .limit(limit)
                         .forEach(studyDir -> {
                             try {
                                 Path file = studyDir.resolve(REJECTION_METADATA_FILE);
                                 if (Files.exists(file)) {
                                     RejectionMetadata metadata = objectMapper.readValue(
                                             file.toFile(), RejectionMetadata.class);
                                     rejected.add(metadata);
                                 }
                             } catch (Exception e) {
                                 log.warn("Failed to load rejection metadata from {}: {}", studyDir, e.getMessage());
                             }
                         });
            }

            // Sort by rejection time (newest first)
            rejected.sort((a, b) -> {
                if (a.getRejectedAt() == null) return 1;
                if (b.getRejectedAt() == null) return -1;
                return b.getRejectedAt().compareTo(a.getRejectedAt());
            });

        } catch (IOException e) {
            log.error("[{}] Failed to list rejected studies: {}", aeTitle, e.getMessage(), e);
        }

        return rejected;
    }

    /**
     * Check if a route requires review.
     */
    public boolean isReviewRequired(String aeTitle) {
        AppConfig.RouteConfig route = config.findRouteByAeTitle(aeTitle);
        return route != null && route.isRequireReview();
    }

    /**
     * Get count of pending reviews for an AE title.
     */
    public int getPendingReviewCount(String aeTitle) {
        try {
            Path pendingDir = baseDir.resolve(aeTitle).resolve(PENDING_REVIEW_DIR);
            if (!Files.exists(pendingDir)) {
                return 0;
            }

            try (Stream<Path> dirs = Files.list(pendingDir)) {
                return (int) dirs.filter(Files::isDirectory)
                                  .filter(p -> p.getFileName().toString().startsWith("study_"))
                                  .count();
            }
        } catch (IOException e) {
            log.warn("[{}] Failed to count pending reviews: {}", aeTitle, e.getMessage());
            return 0;
        }
    }

    // Private helpers

    private Path getPendingReviewDir(String aeTitle, String studyUid) {
        String studyDirName = "study_" + sanitizeUid(studyUid);
        return baseDir.resolve(aeTitle).resolve(PENDING_REVIEW_DIR).resolve(studyDirName);
    }

    private Path getRejectedDir(String aeTitle, String studyUid) {
        String studyDirName = "study_" + sanitizeUid(studyUid);
        return baseDir.resolve(aeTitle).resolve(REJECTED_DIR).resolve(studyDirName);
    }

    private String sanitizeUid(String uid) {
        return uid.replaceAll("[^a-zA-Z0-9.-]", "_");
    }

    private ReviewMetadata loadReviewMetadata(Path studyDir) {
        try {
            Path file = studyDir.resolve(REVIEW_METADATA_FILE);
            if (!Files.exists(file)) {
                return null;
            }
            return objectMapper.readValue(file.toFile(), ReviewMetadata.class);
        } catch (IOException e) {
            log.warn("Failed to load review metadata: {}", e.getMessage());
            return null;
        }
    }

    private void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;

        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // Data classes

    public enum ReviewStatus {
        PENDING_REVIEW,
        APPROVED,
        REJECTED
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReviewMetadata {
        private String reviewId;
        private String studyUid;
        private String aeTitle;
        private String archivePath;
        private LocalDateTime submittedAt;
        private ReviewStatus status;
        private String scriptUsed;
        private int phiFieldsModified;
        private List<String> warnings;
        private LocalDateTime reviewedAt;
        private String reviewedBy;
        private String reviewNotes;
        private String rejectionReason;

        public String getReviewId() { return reviewId; }
        public void setReviewId(String reviewId) { this.reviewId = reviewId; }

        public String getStudyUid() { return studyUid; }
        public void setStudyUid(String studyUid) { this.studyUid = studyUid; }

        public String getAeTitle() { return aeTitle; }
        public void setAeTitle(String aeTitle) { this.aeTitle = aeTitle; }

        public String getArchivePath() { return archivePath; }
        public void setArchivePath(String archivePath) { this.archivePath = archivePath; }

        public LocalDateTime getSubmittedAt() { return submittedAt; }
        public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }

        public ReviewStatus getStatus() { return status; }
        public void setStatus(ReviewStatus status) { this.status = status; }

        public String getScriptUsed() { return scriptUsed; }
        public void setScriptUsed(String scriptUsed) { this.scriptUsed = scriptUsed; }

        public int getPhiFieldsModified() { return phiFieldsModified; }
        public void setPhiFieldsModified(int phiFieldsModified) { this.phiFieldsModified = phiFieldsModified; }

        public List<String> getWarnings() { return warnings; }
        public void setWarnings(List<String> warnings) { this.warnings = warnings; }

        public LocalDateTime getReviewedAt() { return reviewedAt; }
        public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }

        public String getReviewedBy() { return reviewedBy; }
        public void setReviewedBy(String reviewedBy) { this.reviewedBy = reviewedBy; }

        public String getReviewNotes() { return reviewNotes; }
        public void setReviewNotes(String reviewNotes) { this.reviewNotes = reviewNotes; }

        public String getRejectionReason() { return rejectionReason; }
        public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RejectionMetadata {
        private String studyUid;
        private String aeTitle;
        private String reviewId;
        private LocalDateTime rejectedAt;
        private String rejectedBy;
        private String rejectionReason;
        private LocalDateTime originalSubmittedAt;
        private String scriptUsed;
        private String archivePath;

        public String getStudyUid() { return studyUid; }
        public void setStudyUid(String studyUid) { this.studyUid = studyUid; }

        public String getAeTitle() { return aeTitle; }
        public void setAeTitle(String aeTitle) { this.aeTitle = aeTitle; }

        public String getReviewId() { return reviewId; }
        public void setReviewId(String reviewId) { this.reviewId = reviewId; }

        public LocalDateTime getRejectedAt() { return rejectedAt; }
        public void setRejectedAt(LocalDateTime rejectedAt) { this.rejectedAt = rejectedAt; }

        public String getRejectedBy() { return rejectedBy; }
        public void setRejectedBy(String rejectedBy) { this.rejectedBy = rejectedBy; }

        public String getRejectionReason() { return rejectionReason; }
        public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

        public LocalDateTime getOriginalSubmittedAt() { return originalSubmittedAt; }
        public void setOriginalSubmittedAt(LocalDateTime originalSubmittedAt) { this.originalSubmittedAt = originalSubmittedAt; }

        public String getScriptUsed() { return scriptUsed; }
        public void setScriptUsed(String scriptUsed) { this.scriptUsed = scriptUsed; }

        public String getArchivePath() { return archivePath; }
        public void setArchivePath(String archivePath) { this.archivePath = archivePath; }
    }

    /**
     * Callback interface for approval events.
     */
    public interface ReviewApprovalCallback {
        void onApproval(ReviewMetadata review, ArchiveManager.ArchivedStudy study) throws Exception;
    }
}
