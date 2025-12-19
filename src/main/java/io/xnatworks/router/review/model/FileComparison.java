/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.review.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a pair of original and anonymized DICOM files for comparison.
 */
public class FileComparison {

    private String sopInstanceUid;
    private String originalFile;
    private String anonymizedFile;
    private int instanceNumber;
    private boolean hasOriginal;
    private boolean hasAnonymized;

    public FileComparison() {
    }

    public FileComparison(String sopInstanceUid, String originalFile, String anonymizedFile, int instanceNumber) {
        this.sopInstanceUid = sopInstanceUid;
        this.originalFile = originalFile;
        this.anonymizedFile = anonymizedFile;
        this.instanceNumber = instanceNumber;
        this.hasOriginal = originalFile != null;
        this.hasAnonymized = anonymizedFile != null;
    }

    @JsonProperty("sopInstanceUid")
    public String getSopInstanceUid() {
        return sopInstanceUid;
    }

    public void setSopInstanceUid(String sopInstanceUid) {
        this.sopInstanceUid = sopInstanceUid;
    }

    @JsonProperty("originalFile")
    public String getOriginalFile() {
        return originalFile;
    }

    public void setOriginalFile(String originalFile) {
        this.originalFile = originalFile;
        this.hasOriginal = originalFile != null;
    }

    @JsonProperty("anonymizedFile")
    public String getAnonymizedFile() {
        return anonymizedFile;
    }

    public void setAnonymizedFile(String anonymizedFile) {
        this.anonymizedFile = anonymizedFile;
        this.hasAnonymized = anonymizedFile != null;
    }

    @JsonProperty("instanceNumber")
    public int getInstanceNumber() {
        return instanceNumber;
    }

    public void setInstanceNumber(int instanceNumber) {
        this.instanceNumber = instanceNumber;
    }

    @JsonProperty("hasOriginal")
    public boolean isHasOriginal() {
        return hasOriginal;
    }

    public void setHasOriginal(boolean hasOriginal) {
        this.hasOriginal = hasOriginal;
    }

    @JsonProperty("hasAnonymized")
    public boolean isHasAnonymized() {
        return hasAnonymized;
    }

    public void setHasAnonymized(boolean hasAnonymized) {
        this.hasAnonymized = hasAnonymized;
    }

    @Override
    public String toString() {
        return String.format("FileComparison{sop=%s, instance=%d, orig=%s, anon=%s}",
                sopInstanceUid, instanceNumber,
                hasOriginal ? "yes" : "no",
                hasAnonymized ? "yes" : "no");
    }
}
