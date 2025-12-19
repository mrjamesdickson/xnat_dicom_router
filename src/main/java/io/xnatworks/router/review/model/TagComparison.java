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
 * Represents a comparison of a single DICOM tag between original and anonymized files.
 */
public class TagComparison {

    private String tag;           // (0010,0010)
    private String name;          // PatientName
    private String vr;            // PN
    private String category;      // Patient, Study, Series, Equipment, Image, Other
    private String originalValue;
    private String anonymizedValue;
    private boolean changed;
    private boolean removed;
    private boolean added;
    private boolean phi;

    public TagComparison() {
    }

    public TagComparison(String tag, String name, String vr, String category,
                         String originalValue, String anonymizedValue,
                         boolean changed, boolean removed, boolean added, boolean phi) {
        this.tag = tag;
        this.name = name;
        this.vr = vr;
        this.category = category;
        this.originalValue = originalValue;
        this.anonymizedValue = anonymizedValue;
        this.changed = changed;
        this.removed = removed;
        this.added = added;
        this.phi = phi;
    }

    @JsonProperty("tag")
    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    @JsonProperty("name")
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @JsonProperty("vr")
    public String getVr() {
        return vr;
    }

    public void setVr(String vr) {
        this.vr = vr;
    }

    @JsonProperty("category")
    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    @JsonProperty("originalValue")
    public String getOriginalValue() {
        return originalValue;
    }

    public void setOriginalValue(String originalValue) {
        this.originalValue = originalValue;
    }

    @JsonProperty("anonymizedValue")
    public String getAnonymizedValue() {
        return anonymizedValue;
    }

    public void setAnonymizedValue(String anonymizedValue) {
        this.anonymizedValue = anonymizedValue;
    }

    @JsonProperty("changed")
    public boolean isChanged() {
        return changed;
    }

    public void setChanged(boolean changed) {
        this.changed = changed;
    }

    @JsonProperty("removed")
    public boolean isRemoved() {
        return removed;
    }

    public void setRemoved(boolean removed) {
        this.removed = removed;
    }

    @JsonProperty("added")
    public boolean isAdded() {
        return added;
    }

    public void setAdded(boolean added) {
        this.added = added;
    }

    @JsonProperty("isPhi")
    public boolean isPhi() {
        return phi;
    }

    public void setPhi(boolean phi) {
        this.phi = phi;
    }

    @Override
    public String toString() {
        return String.format("TagComparison{%s %s: '%s' -> '%s' [%s%s%s]}",
                tag, name,
                truncate(originalValue, 20),
                truncate(anonymizedValue, 20),
                changed ? "CHANGED" : "",
                removed ? "REMOVED" : "",
                added ? "ADDED" : "");
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }
}
