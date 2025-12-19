/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.review.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a comparison of a DICOM study between original and anonymized files.
 * Contains metadata and list of scan comparisons.
 */
public class StudyComparison {

    private String studyUid;
    private String patientId;
    private String patientName;
    private String studyDate;
    private String studyDescription;
    private String accessionNumber;
    private String originalPath;
    private String anonymizedPath;
    private int scanCount;
    private int fileCount;
    private String aeTitle;
    private String reviewId;
    private String reviewStatus;
    private LocalDateTime submittedAt;

    // Anonymization info
    private String scriptUsed;
    private int phiFieldsModified;
    private List<String> warnings = new ArrayList<>();

    // OCR info
    private int ocrDetectedRegions;
    private boolean hasPixelPhi;

    public StudyComparison() {
    }

    @JsonProperty("studyUid")
    public String getStudyUid() {
        return studyUid;
    }

    public void setStudyUid(String studyUid) {
        this.studyUid = studyUid;
    }

    @JsonProperty("patientId")
    public String getPatientId() {
        return patientId;
    }

    public void setPatientId(String patientId) {
        this.patientId = patientId;
    }

    @JsonProperty("patientName")
    public String getPatientName() {
        return patientName;
    }

    public void setPatientName(String patientName) {
        this.patientName = patientName;
    }

    @JsonProperty("studyDate")
    public String getStudyDate() {
        return studyDate;
    }

    public void setStudyDate(String studyDate) {
        this.studyDate = studyDate;
    }

    @JsonProperty("studyDescription")
    public String getStudyDescription() {
        return studyDescription;
    }

    public void setStudyDescription(String studyDescription) {
        this.studyDescription = studyDescription;
    }

    @JsonProperty("accessionNumber")
    public String getAccessionNumber() {
        return accessionNumber;
    }

    public void setAccessionNumber(String accessionNumber) {
        this.accessionNumber = accessionNumber;
    }

    @JsonProperty("originalPath")
    public String getOriginalPath() {
        return originalPath;
    }

    public void setOriginalPath(String originalPath) {
        this.originalPath = originalPath;
    }

    @JsonProperty("anonymizedPath")
    public String getAnonymizedPath() {
        return anonymizedPath;
    }

    public void setAnonymizedPath(String anonymizedPath) {
        this.anonymizedPath = anonymizedPath;
    }

    @JsonProperty("scanCount")
    public int getScanCount() {
        return scanCount;
    }

    public void setScanCount(int scanCount) {
        this.scanCount = scanCount;
    }

    @JsonProperty("fileCount")
    public int getFileCount() {
        return fileCount;
    }

    public void setFileCount(int fileCount) {
        this.fileCount = fileCount;
    }

    @JsonProperty("aeTitle")
    public String getAeTitle() {
        return aeTitle;
    }

    public void setAeTitle(String aeTitle) {
        this.aeTitle = aeTitle;
    }

    @JsonProperty("reviewId")
    public String getReviewId() {
        return reviewId;
    }

    public void setReviewId(String reviewId) {
        this.reviewId = reviewId;
    }

    @JsonProperty("reviewStatus")
    public String getReviewStatus() {
        return reviewStatus;
    }

    public void setReviewStatus(String reviewStatus) {
        this.reviewStatus = reviewStatus;
    }

    @JsonProperty("submittedAt")
    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(LocalDateTime submittedAt) {
        this.submittedAt = submittedAt;
    }

    @JsonProperty("scriptUsed")
    public String getScriptUsed() {
        return scriptUsed;
    }

    public void setScriptUsed(String scriptUsed) {
        this.scriptUsed = scriptUsed;
    }

    @JsonProperty("phiFieldsModified")
    public int getPhiFieldsModified() {
        return phiFieldsModified;
    }

    public void setPhiFieldsModified(int phiFieldsModified) {
        this.phiFieldsModified = phiFieldsModified;
    }

    @JsonProperty("warnings")
    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings;
    }

    @JsonProperty("ocrDetectedRegions")
    public int getOcrDetectedRegions() {
        return ocrDetectedRegions;
    }

    public void setOcrDetectedRegions(int ocrDetectedRegions) {
        this.ocrDetectedRegions = ocrDetectedRegions;
    }

    @JsonProperty("hasPixelPhi")
    public boolean isHasPixelPhi() {
        return hasPixelPhi;
    }

    public void setHasPixelPhi(boolean hasPixelPhi) {
        this.hasPixelPhi = hasPixelPhi;
    }

    @Override
    public String toString() {
        return String.format("StudyComparison{uid=%s, patient='%s', scans=%d, files=%d}",
                studyUid, patientName, scanCount, fileCount);
    }
}
