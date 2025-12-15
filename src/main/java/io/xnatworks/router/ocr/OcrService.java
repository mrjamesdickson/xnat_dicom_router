/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */
package io.xnatworks.router.ocr;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.Word;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Service for OCR-based PHI detection in DICOM images using Tesseract.
 * Detects text burned into pixel data and generates AlterPixel commands
 * to redact the detected regions.
 */
public class OcrService {
    private static final Logger log = LoggerFactory.getLogger(OcrService.class);

    private final ITesseract tesseract;
    private final String tessDataPath;
    private final PhiPatternMatcher phiMatcher;
    private boolean initialized = false;

    // Default confidence threshold for OCR results (0-100)
    private static final float DEFAULT_CONFIDENCE_THRESHOLD = 60.0f;

    /**
     * Create OCR service with default tessdata path.
     * Looks for tessdata in: ./tessdata, /usr/share/tesseract-ocr/4.00/tessdata,
     * /usr/local/share/tessdata
     */
    public OcrService() {
        this(findTessDataPath());
    }

    /**
     * Create OCR service with specified tessdata path.
     * @param tessDataPath Path to tessdata directory containing trained data files
     */
    public OcrService(String tessDataPath) {
        this.tessDataPath = tessDataPath;
        this.tesseract = new Tesseract();
        this.phiMatcher = new PhiPatternMatcher();

        if (tessDataPath != null && !tessDataPath.isEmpty()) {
            tesseract.setDatapath(tessDataPath);
        }

        // Configure Tesseract for best text detection
        tesseract.setLanguage("eng");
        tesseract.setPageSegMode(3); // PSM_AUTO - Fully automatic page segmentation
        tesseract.setOcrEngineMode(1); // OEM_LSTM_ONLY - Use LSTM neural network

        // Set variables for better accuracy
        tesseract.setVariable("tessedit_char_whitelist",
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-/:., ");

        log.info("OCR Service initialized with tessdata path: {}", tessDataPath);
        initialized = true;
    }

    /**
     * Find tessdata directory from common locations.
     */
    private static String findTessDataPath() {
        String[] possiblePaths = {
            System.getenv("TESSDATA_PREFIX"),
            "./tessdata",
            "/usr/share/tesseract-ocr/4.00/tessdata",
            "/usr/share/tesseract-ocr/5/tessdata",
            "/usr/local/share/tessdata",
            "/opt/homebrew/share/tessdata",
            "/usr/local/Cellar/tesseract/5.3.3/share/tessdata"
        };

        for (String path : possiblePaths) {
            if (path != null) {
                File dir = new File(path);
                if (dir.exists() && dir.isDirectory()) {
                    File engFile = new File(dir, "eng.traineddata");
                    if (engFile.exists()) {
                        log.info("Found tessdata at: {}", path);
                        return path;
                    }
                }
            }
        }

        log.warn("Could not find tessdata directory. OCR may not work correctly.");
        return null;
    }

    /**
     * Check if the OCR service is properly initialized and ready.
     */
    public boolean isAvailable() {
        if (!initialized || tessDataPath == null) {
            return false;
        }
        File engData = new File(tessDataPath, "eng.traineddata");
        return engData.exists();
    }

    /**
     * Detect text regions in an image and classify them as potential PHI.
     *
     * @param image The image to analyze
     * @return List of detected text regions with PHI classification
     */
    public List<DetectedRegion> detectText(BufferedImage image) {
        return detectText(image, DEFAULT_CONFIDENCE_THRESHOLD);
    }

    /**
     * Detect text regions in an image with custom confidence threshold.
     *
     * @param image The image to analyze
     * @param confidenceThreshold Minimum confidence (0-100) for including results
     * @return List of detected text regions with PHI classification
     */
    public List<DetectedRegion> detectText(BufferedImage image, float confidenceThreshold) {
        List<DetectedRegion> regions = new ArrayList<>();

        if (!isAvailable()) {
            log.warn("OCR service not available - tessdata not found");
            return regions;
        }

        try {
            // Preprocess image for better OCR
            BufferedImage processedImage = preprocessImage(image);

            // Get word-level results with bounding boxes
            List<Word> words = tesseract.getWords(processedImage, net.sourceforge.tess4j.ITessAPI.TessPageIteratorLevel.RIL_WORD);

            for (Word word : words) {
                if (word.getConfidence() >= confidenceThreshold) {
                    String text = word.getText().trim();
                    if (text.isEmpty()) continue;

                    Rectangle bbox = word.getBoundingBox();
                    boolean isPhi = phiMatcher.isPotentialPhi(text);

                    DetectedRegion region = new DetectedRegion(
                        bbox.x,
                        bbox.y,
                        bbox.width,
                        bbox.height,
                        text,
                        word.getConfidence(),
                        isPhi
                    );
                    regions.add(region);

                    log.debug("Detected text: '{}' at ({},{}) {}x{} confidence={} isPHI={}",
                        text, bbox.x, bbox.y, bbox.width, bbox.height,
                        word.getConfidence(), isPhi);
                }
            }

            // Also check line-level for multi-word PHI patterns
            List<Word> lines = tesseract.getWords(processedImage, net.sourceforge.tess4j.ITessAPI.TessPageIteratorLevel.RIL_TEXTLINE);
            for (Word line : lines) {
                if (line.getConfidence() >= confidenceThreshold) {
                    String text = line.getText().trim();
                    if (text.length() > 2 && phiMatcher.isPotentialPhi(text)) {
                        Rectangle bbox = line.getBoundingBox();

                        // Check if this line region isn't already covered by word regions
                        boolean alreadyCovered = regions.stream()
                            .anyMatch(r -> r.isPhi() && overlaps(r, bbox));

                        if (!alreadyCovered) {
                            DetectedRegion region = new DetectedRegion(
                                bbox.x, bbox.y, bbox.width, bbox.height,
                                text, line.getConfidence(), true
                            );
                            regions.add(region);
                        }
                    }
                }
            }

            log.info("OCR detected {} text regions, {} classified as potential PHI",
                regions.size(), regions.stream().filter(DetectedRegion::isPhi).count());

        } catch (Exception e) {
            log.error("OCR processing failed: {}", e.getMessage(), e);
        }

        return regions;
    }

    /**
     * Detect text in a DICOM image file.
     */
    public List<DetectedRegion> detectTextInFile(File imageFile) throws IOException {
        BufferedImage image = ImageIO.read(imageFile);
        if (image == null) {
            throw new IOException("Could not read image file: " + imageFile);
        }
        return detectText(image);
    }

    /**
     * Generate DicomEdit AlterPixel commands from detected PHI regions.
     *
     * @param regions List of detected regions
     * @param imageWidth Original image width
     * @param imageHeight Original image height
     * @param padding Pixels to add around each region
     * @return DicomEdit script with alterPixels commands
     */
    public String generateAlterPixelScript(List<DetectedRegion> regions,
                                           int imageWidth, int imageHeight, int padding) {
        List<DetectedRegion> phiRegions = new ArrayList<>();
        for (DetectedRegion r : regions) {
            if (r.isPhi()) {
                phiRegions.add(r);
            }
        }

        if (phiRegions.isEmpty()) {
            return "// No PHI regions detected for burn-in removal\n";
        }

        // Merge overlapping regions
        List<DetectedRegion> merged = mergeOverlappingRegions(phiRegions, padding);

        StringBuilder script = new StringBuilder();
        script.append("// Pixel burn-in removal script\n");
        script.append("// Generated by OCR detection\n");
        script.append("// Image dimensions: ").append(imageWidth).append(" x ").append(imageHeight).append("\n");
        script.append("// Detected ").append(merged.size()).append(" region(s) with potential PHI\n\n");

        for (int i = 0; i < merged.size(); i++) {
            DetectedRegion region = merged.get(i);

            // Apply padding and clamp to image bounds
            int x = Math.max(0, region.getX() - padding);
            int y = Math.max(0, region.getY() - padding);
            int w = Math.min(imageWidth - x, region.getWidth() + padding * 2);
            int h = Math.min(imageHeight - y, region.getHeight() + padding * 2);

            String truncatedText = region.getText();
            if (truncatedText.length() > 30) {
                truncatedText = truncatedText.substring(0, 30) + "...";
            }

            script.append("// Region ").append(i + 1).append(": \"").append(truncatedText)
                  .append("\" (confidence: ").append(String.format("%.1f", region.getConfidence())).append("%)\n");
            script.append("alterPixels[").append(x).append(", ").append(y)
                  .append(", ").append(w).append(", ").append(h).append("]\n\n");
        }

        return script.toString();
    }

    /**
     * Preprocess image for better OCR results.
     * Applies grayscale conversion, contrast enhancement, and optional binarization.
     */
    private BufferedImage preprocessImage(BufferedImage original) {
        int width = original.getWidth();
        int height = original.getHeight();

        // Create grayscale image with enhanced contrast
        BufferedImage processed = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = processed.createGraphics();
        g.drawImage(original, 0, 0, null);
        g.dispose();

        // Apply contrast enhancement
        int[] pixels = processed.getRaster().getPixels(0, 0, width, height, (int[]) null);

        // Find min/max for contrast stretching
        int min = 255, max = 0;
        for (int p : pixels) {
            if (p < min) min = p;
            if (p > max) max = p;
        }

        // Apply contrast stretch if needed
        if (max > min) {
            float scale = 255.0f / (max - min);
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = Math.min(255, Math.max(0, (int) ((pixels[i] - min) * scale)));
            }
            processed.getRaster().setPixels(0, 0, width, height, pixels);
        }

        return processed;
    }

    /**
     * Check if a region overlaps with a bounding box.
     */
    private boolean overlaps(DetectedRegion region, Rectangle bbox) {
        return region.getX() < bbox.x + bbox.width &&
               region.getX() + region.getWidth() > bbox.x &&
               region.getY() < bbox.y + bbox.height &&
               region.getY() + region.getHeight() > bbox.y;
    }

    /**
     * Merge overlapping regions to reduce redundant redaction areas.
     */
    private List<DetectedRegion> mergeOverlappingRegions(List<DetectedRegion> regions, int padding) {
        if (regions.isEmpty()) return regions;

        // Sort by Y then X
        List<DetectedRegion> sorted = new ArrayList<>(regions);
        sorted.sort((a, b) -> {
            int cmp = Integer.compare(a.getY(), b.getY());
            return cmp != 0 ? cmp : Integer.compare(a.getX(), b.getX());
        });

        List<DetectedRegion> merged = new ArrayList<>();
        DetectedRegion current = sorted.get(0);

        for (int i = 1; i < sorted.size(); i++) {
            DetectedRegion next = sorted.get(i);

            // Check if regions overlap (with padding tolerance)
            boolean overlaps =
                next.getX() <= current.getX() + current.getWidth() + padding &&
                next.getX() + next.getWidth() >= current.getX() - padding &&
                next.getY() <= current.getY() + current.getHeight() + padding &&
                next.getY() + next.getHeight() >= current.getY() - padding;

            if (overlaps) {
                // Merge by expanding bounding box
                int x1 = Math.min(current.getX(), next.getX());
                int y1 = Math.min(current.getY(), next.getY());
                int x2 = Math.max(current.getX() + current.getWidth(),
                                  next.getX() + next.getWidth());
                int y2 = Math.max(current.getY() + current.getHeight(),
                                  next.getY() + next.getHeight());

                current = new DetectedRegion(
                    x1, y1, x2 - x1, y2 - y1,
                    current.getText() + " " + next.getText(),
                    Math.max(current.getConfidence(), next.getConfidence()),
                    true
                );
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);

        return merged;
    }

    /**
     * Get the tessdata path being used.
     */
    public String getTessDataPath() {
        return tessDataPath;
    }
}
