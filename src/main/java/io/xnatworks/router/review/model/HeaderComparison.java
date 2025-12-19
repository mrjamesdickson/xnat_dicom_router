/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.review.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a full comparison of DICOM headers between original and anonymized files.
 */
public class HeaderComparison {

    private List<TagComparison> tags = new ArrayList<>();
    private int totalTags;
    private int changedTags;
    private int removedTags;
    private int addedTags;
    private int phiTags;
    private String originalFile;
    private String anonymizedFile;

    public HeaderComparison() {
    }

    @JsonProperty("tags")
    public List<TagComparison> getTags() {
        return tags;
    }

    public void setTags(List<TagComparison> tags) {
        this.tags = tags;
    }

    public void addTag(TagComparison tag) {
        this.tags.add(tag);
    }

    @JsonProperty("totalTags")
    public int getTotalTags() {
        return totalTags;
    }

    public void setTotalTags(int totalTags) {
        this.totalTags = totalTags;
    }

    @JsonProperty("changedTags")
    public int getChangedTags() {
        return changedTags;
    }

    public void setChangedTags(int changedTags) {
        this.changedTags = changedTags;
    }

    @JsonProperty("removedTags")
    public int getRemovedTags() {
        return removedTags;
    }

    public void setRemovedTags(int removedTags) {
        this.removedTags = removedTags;
    }

    @JsonProperty("addedTags")
    public int getAddedTags() {
        return addedTags;
    }

    public void setAddedTags(int addedTags) {
        this.addedTags = addedTags;
    }

    @JsonProperty("phiTags")
    public int getPhiTags() {
        return phiTags;
    }

    public void setPhiTags(int phiTags) {
        this.phiTags = phiTags;
    }

    @JsonProperty("originalFile")
    public String getOriginalFile() {
        return originalFile;
    }

    public void setOriginalFile(String originalFile) {
        this.originalFile = originalFile;
    }

    @JsonProperty("anonymizedFile")
    public String getAnonymizedFile() {
        return anonymizedFile;
    }

    public void setAnonymizedFile(String anonymizedFile) {
        this.anonymizedFile = anonymizedFile;
    }

    /**
     * Compute statistics from the tags list.
     */
    public void computeStats() {
        this.totalTags = tags.size();
        this.changedTags = 0;
        this.removedTags = 0;
        this.addedTags = 0;
        this.phiTags = 0;

        for (TagComparison tag : tags) {
            if (tag.isChanged()) changedTags++;
            if (tag.isRemoved()) removedTags++;
            if (tag.isAdded()) addedTags++;
            if (tag.isPhi()) phiTags++;
        }
    }

    @Override
    public String toString() {
        return String.format("HeaderComparison{total=%d, changed=%d, removed=%d, added=%d, phi=%d}",
                totalTags, changedTags, removedTags, addedTags, phiTags);
    }
}
