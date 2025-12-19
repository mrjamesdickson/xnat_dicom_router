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
 * Represents a comparison of a DICOM series/scan between original and anonymized files.
 */
public class ScanComparison {

    private String seriesUid;
    private String seriesDescription;
    private String modality;
    private int seriesNumber;
    private int instanceCount;
    private List<FileComparison> files = new ArrayList<>();

    public ScanComparison() {
    }

    @JsonProperty("seriesUid")
    public String getSeriesUid() {
        return seriesUid;
    }

    public void setSeriesUid(String seriesUid) {
        this.seriesUid = seriesUid;
    }

    @JsonProperty("seriesDescription")
    public String getSeriesDescription() {
        return seriesDescription;
    }

    public void setSeriesDescription(String seriesDescription) {
        this.seriesDescription = seriesDescription;
    }

    @JsonProperty("modality")
    public String getModality() {
        return modality;
    }

    public void setModality(String modality) {
        this.modality = modality;
    }

    @JsonProperty("seriesNumber")
    public int getSeriesNumber() {
        return seriesNumber;
    }

    public void setSeriesNumber(int seriesNumber) {
        this.seriesNumber = seriesNumber;
    }

    @JsonProperty("instanceCount")
    public int getInstanceCount() {
        return instanceCount;
    }

    public void setInstanceCount(int instanceCount) {
        this.instanceCount = instanceCount;
    }

    @JsonProperty("files")
    public List<FileComparison> getFiles() {
        return files;
    }

    public void setFiles(List<FileComparison> files) {
        this.files = files;
    }

    public void addFile(FileComparison file) {
        this.files.add(file);
        this.instanceCount = this.files.size();
    }

    @Override
    public String toString() {
        return String.format("ScanComparison{series=%s, desc='%s', mod=%s, files=%d}",
                seriesUid, seriesDescription, modality, instanceCount);
    }
}
