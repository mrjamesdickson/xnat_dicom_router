/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */
package io.xnatworks.router.ocr;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a text region detected by OCR in a DICOM image.
 * Contains the bounding box coordinates, detected text, confidence level,
 * and whether the text was classified as potential PHI.
 */
public class DetectedRegion {

    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private final String text;
    private final float confidence;
    private final boolean phi;

    /**
     * Create a new detected region.
     *
     * @param x Left coordinate of bounding box
     * @param y Top coordinate of bounding box
     * @param width Width of bounding box
     * @param height Height of bounding box
     * @param text Detected text content
     * @param confidence OCR confidence (0-100)
     * @param phi Whether classified as potential PHI
     */
    public DetectedRegion(int x, int y, int width, int height,
                          String text, float confidence, boolean phi) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.text = text;
        this.confidence = confidence;
        this.phi = phi;
    }

    @JsonProperty("x")
    public int getX() {
        return x;
    }

    @JsonProperty("y")
    public int getY() {
        return y;
    }

    @JsonProperty("width")
    public int getWidth() {
        return width;
    }

    @JsonProperty("height")
    public int getHeight() {
        return height;
    }

    @JsonProperty("text")
    public String getText() {
        return text;
    }

    @JsonProperty("confidence")
    public float getConfidence() {
        return confidence;
    }

    @JsonProperty("isPhi")
    public boolean isPhi() {
        return phi;
    }

    /**
     * Check if this region overlaps with another region.
     */
    public boolean overlaps(DetectedRegion other, int padding) {
        return this.x - padding < other.x + other.width + padding &&
               this.x + this.width + padding > other.x - padding &&
               this.y - padding < other.y + other.height + padding &&
               this.y + this.height + padding > other.y - padding;
    }

    /**
     * Create a merged region from this and another region.
     */
    public DetectedRegion merge(DetectedRegion other) {
        int x1 = Math.min(this.x, other.x);
        int y1 = Math.min(this.y, other.y);
        int x2 = Math.max(this.x + this.width, other.x + other.width);
        int y2 = Math.max(this.y + this.height, other.y + other.height);

        return new DetectedRegion(
            x1, y1, x2 - x1, y2 - y1,
            this.text + " " + other.text,
            Math.max(this.confidence, other.confidence),
            this.phi || other.phi
        );
    }

    @Override
    public String toString() {
        return String.format("DetectedRegion{x=%d, y=%d, w=%d, h=%d, text='%s', conf=%.1f, phi=%b}",
            x, y, width, height,
            text.length() > 20 ? text.substring(0, 20) + "..." : text,
            confidence, phi);
    }
}
