/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */
package io.xnatworks.router.ocr;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.io.DicomOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.DataBufferUShort;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Processor for running OCR on DICOM images and applying pixel redaction.
 * Extracts pixel data from DICOM files, runs Tesseract OCR to detect text,
 * and can apply black box redaction to PHI regions.
 */
public class DicomOcrProcessor {
    private static final Logger log = LoggerFactory.getLogger(DicomOcrProcessor.class);

    private final OcrService ocrService;
    private final int padding;
    private final float confidenceThreshold;

    /**
     * Create processor with default settings.
     */
    public DicomOcrProcessor() {
        this(new OcrService(), 2, 60.0f);
    }

    /**
     * Create processor with specified OCR service and settings.
     *
     * @param ocrService The OCR service to use
     * @param padding Pixels to add around detected regions
     * @param confidenceThreshold Minimum OCR confidence (0-100)
     */
    public DicomOcrProcessor(OcrService ocrService, int padding, float confidenceThreshold) {
        this.ocrService = ocrService;
        this.padding = padding;
        this.confidenceThreshold = confidenceThreshold;
    }

    /**
     * Check if OCR is available.
     */
    public boolean isAvailable() {
        return ocrService.isAvailable();
    }

    /**
     * Process a DICOM file and detect PHI in pixel data.
     * Handles both uncompressed and JPEG-compressed transfer syntaxes.
     *
     * @param dicomFile The DICOM file to process
     * @return OCR results including detected regions
     */
    public OcrResult processDicomFile(File dicomFile) throws IOException {
        log.info("Processing DICOM file for OCR: {}", dicomFile.getName());

        OcrResult result = new OcrResult();
        result.setSourceName(dicomFile.getName());

        try (DicomInputStream dis = new DicomInputStream(dicomFile)) {
            dis.setIncludeBulkData(DicomInputStream.IncludeBulkData.YES);
            Attributes fmi = dis.readFileMetaInformation();
            Attributes attrs = dis.readDataset();

            String transferSyntax = fmi != null ? fmi.getString(Tag.TransferSyntaxUID) : null;
            log.debug("Transfer syntax: {}", transferSyntax);

            BufferedImage image = null;

            // Check if it's a JPEG compressed transfer syntax
            if (isJpegTransferSyntax(transferSyntax)) {
                // Extract embedded JPEG from encapsulated pixel data
                image = extractJpegImage(attrs, dicomFile);
            } else {
                // Try uncompressed pixel data extraction
                image = extractUncompressedImage(attrs);
            }

            if (image == null) {
                result.setError("Failed to extract image from DICOM");
                return result;
            }

            result.setImageWidth(image.getWidth());
            result.setImageHeight(image.getHeight());

            log.debug("Extracted DICOM image: {}x{}", image.getWidth(), image.getHeight());

            // Run OCR
            List<DetectedRegion> regions = ocrService.detectText(image, confidenceThreshold);
            result.setDetectedRegions(regions);

            // Count PHI regions
            long phiCount = regions.stream().filter(DetectedRegion::isPhi).count();
            result.setPhiRegionCount((int) phiCount);

            // Generate AlterPixel script
            if (phiCount > 0) {
                String script = ocrService.generateAlterPixelScript(regions, image.getWidth(), image.getHeight(), padding);
                result.setAlterPixelScript(script);
            }

            log.info("OCR completed for {}: {} regions detected, {} classified as PHI",
                    dicomFile.getName(), regions.size(), phiCount);

        } catch (Exception e) {
            log.error("OCR processing failed for {}: {}", dicomFile.getName(), e.getMessage(), e);
            result.setError(e.getMessage());
        }

        return result;
    }

    /**
     * Check if transfer syntax is JPEG-based.
     */
    private boolean isJpegTransferSyntax(String tsuid) {
        if (tsuid == null) return false;
        // JPEG transfer syntax UIDs
        return tsuid.equals("1.2.840.10008.1.2.4.50") ||  // JPEG Baseline
               tsuid.equals("1.2.840.10008.1.2.4.51") ||  // JPEG Extended
               tsuid.equals("1.2.840.10008.1.2.4.57") ||  // JPEG Lossless
               tsuid.equals("1.2.840.10008.1.2.4.70") ||  // JPEG Lossless SV1
               tsuid.equals("1.2.840.10008.1.2.4.80") ||  // JPEG-LS Lossless
               tsuid.equals("1.2.840.10008.1.2.4.81") ||  // JPEG-LS Near Lossless
               tsuid.equals("1.2.840.10008.1.2.4.90") ||  // JPEG 2000 Lossless
               tsuid.equals("1.2.840.10008.1.2.4.91");    // JPEG 2000
    }

    /**
     * Extract JPEG image from encapsulated pixel data.
     */
    private BufferedImage extractJpegImage(Attributes attrs, File dicomFile) {
        try {
            // Read file and find the JPEG data in pixel data fragments
            byte[] fileBytes = java.nio.file.Files.readAllBytes(dicomFile.toPath());

            // Find JPEG SOI marker (FF D8) after pixel data tag
            int jpegStart = -1;
            int jpegEnd = -1;

            for (int i = 0; i < fileBytes.length - 1; i++) {
                if ((fileBytes[i] & 0xFF) == 0xFF && (fileBytes[i + 1] & 0xFF) == 0xD8) {
                    // Found JPEG SOI
                    jpegStart = i;
                    // Find JPEG EOI (FF D9)
                    for (int j = i + 2; j < fileBytes.length - 1; j++) {
                        if ((fileBytes[j] & 0xFF) == 0xFF && (fileBytes[j + 1] & 0xFF) == 0xD9) {
                            jpegEnd = j + 2;
                            break;
                        }
                    }
                    break;
                }
            }

            if (jpegStart >= 0 && jpegEnd > jpegStart) {
                byte[] jpegData = new byte[jpegEnd - jpegStart];
                System.arraycopy(fileBytes, jpegStart, jpegData, 0, jpegData.length);

                log.debug("Extracted JPEG data: {} bytes", jpegData.length);

                // Decode JPEG using Java ImageIO
                java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(jpegData);
                BufferedImage image = ImageIO.read(bais);

                if (image != null) {
                    log.debug("Decoded JPEG image: {}x{}", image.getWidth(), image.getHeight());
                    return image;
                }
            }

            log.warn("Could not find embedded JPEG in DICOM file");
            return null;

        } catch (Exception e) {
            log.error("Failed to extract JPEG from DICOM: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extract image from uncompressed pixel data.
     */
    private BufferedImage extractUncompressedImage(Attributes attrs) {
        int rows = attrs.getInt(Tag.Rows, 0);
        int cols = attrs.getInt(Tag.Columns, 0);
        int bitsAllocated = attrs.getInt(Tag.BitsAllocated, 16);
        int samplesPerPixel = attrs.getInt(Tag.SamplesPerPixel, 1);
        String photometric = attrs.getString(Tag.PhotometricInterpretation, "MONOCHROME2");

        if (rows == 0 || cols == 0) {
            log.warn("Invalid image dimensions: {}x{}", cols, rows);
            return null;
        }

        try {
            byte[] pixelData = attrs.getBytes(Tag.PixelData);
            if (pixelData == null) {
                log.warn("No pixel data found");
                return null;
            }

            return convertToBufferedImage(pixelData, cols, rows, bitsAllocated, samplesPerPixel, photometric, attrs);

        } catch (Exception e) {
            log.error("Failed to extract uncompressed image: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Process DICOM attributes and detect PHI in pixel data.
     *
     * @param attrs DICOM attributes containing pixel data
     * @param sourceName Name for logging purposes
     * @return OCR results
     */
    public OcrResult processDicomAttributes(Attributes attrs, String sourceName) {
        OcrResult result = new OcrResult();
        result.setSourceName(sourceName);

        // Get image dimensions
        int rows = attrs.getInt(Tag.Rows, 0);
        int cols = attrs.getInt(Tag.Columns, 0);
        int bitsAllocated = attrs.getInt(Tag.BitsAllocated, 16);
        int samplesPerPixel = attrs.getInt(Tag.SamplesPerPixel, 1);
        String photometric = attrs.getString(Tag.PhotometricInterpretation, "MONOCHROME2");

        result.setImageWidth(cols);
        result.setImageHeight(rows);

        if (rows == 0 || cols == 0) {
            log.warn("Invalid image dimensions: {}x{}", cols, rows);
            result.setError("Invalid image dimensions");
            return result;
        }

        try {
            // Get pixel data
            byte[] pixelData = attrs.getBytes(Tag.PixelData);
            if (pixelData == null) {
                log.warn("No pixel data found in DICOM");
                result.setError("No pixel data");
                return result;
            }

            log.debug("Image: {}x{}, {} bits, {} samples, photometric={}",
                cols, rows, bitsAllocated, samplesPerPixel, photometric);
            // Convert DICOM pixel data to BufferedImage
            BufferedImage image = convertToBufferedImage(
                pixelData, cols, rows, bitsAllocated, samplesPerPixel, photometric, attrs
            );

            if (image == null) {
                result.setError("Failed to convert pixel data to image");
                return result;
            }

            // Run OCR
            List<DetectedRegion> regions = ocrService.detectText(image, confidenceThreshold);
            result.setDetectedRegions(regions);

            // Count PHI regions
            long phiCount = regions.stream().filter(DetectedRegion::isPhi).count();
            result.setPhiRegionCount((int) phiCount);

            // Generate AlterPixel script
            if (phiCount > 0) {
                String script = ocrService.generateAlterPixelScript(regions, cols, rows, padding);
                result.setAlterPixelScript(script);
            }

            log.info("OCR completed for {}: {} regions detected, {} classified as PHI",
                sourceName, regions.size(), phiCount);

        } catch (Exception e) {
            log.error("OCR processing failed: {}", e.getMessage(), e);
            result.setError(e.getMessage());
        }

        return result;
    }

    /**
     * Apply pixel redaction to a DICOM file based on detected regions.
     *
     * @param inputFile Source DICOM file
     * @param outputFile Destination file for redacted DICOM
     * @param regions Regions to redact (only PHI regions will be redacted)
     * @return true if successful
     */
    public boolean applyRedaction(File inputFile, File outputFile, List<DetectedRegion> regions) {
        List<DetectedRegion> phiRegions = new ArrayList<>();
        for (DetectedRegion r : regions) {
            if (r.isPhi()) {
                phiRegions.add(r);
            }
        }

        if (phiRegions.isEmpty()) {
            log.info("No PHI regions to redact");
            return false;
        }

        try (DicomInputStream dis = new DicomInputStream(inputFile)) {
            Attributes attrs = dis.readDataset();
            Attributes fmi = dis.readFileMetaInformation();

            // Get image info
            int rows = attrs.getInt(Tag.Rows, 0);
            int cols = attrs.getInt(Tag.Columns, 0);
            int bitsAllocated = attrs.getInt(Tag.BitsAllocated, 16);
            int samplesPerPixel = attrs.getInt(Tag.SamplesPerPixel, 1);

            // Get pixel data
            byte[] pixelData = attrs.getBytes(Tag.PixelData);
            if (pixelData == null) {
                log.error("No pixel data to redact");
                return false;
            }

            // Apply redaction to each PHI region
            for (DetectedRegion region : phiRegions) {
                int x = Math.max(0, region.getX() - padding);
                int y = Math.max(0, region.getY() - padding);
                int w = Math.min(cols - x, region.getWidth() + padding * 2);
                int h = Math.min(rows - y, region.getHeight() + padding * 2);

                blackoutRegion(pixelData, cols, rows, bitsAllocated, samplesPerPixel, x, y, w, h);

                log.debug("Redacted region: ({},{}) {}x{} - '{}'", x, y, w, h, region.getText());
            }

            // Update pixel data in attributes
            attrs.setBytes(Tag.PixelData, VR.OW, pixelData);

            // Write output file
            try (DicomOutputStream dos = new DicomOutputStream(new FileOutputStream(outputFile), fmi.getString(Tag.TransferSyntaxUID))) {
                dos.writeDataset(fmi, attrs);
            }

            log.info("Redacted {} PHI regions, saved to: {}", phiRegions.size(), outputFile.getName());
            return true;

        } catch (IOException e) {
            log.error("Failed to apply redaction: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Convert DICOM pixel data to a BufferedImage.
     */
    private BufferedImage convertToBufferedImage(byte[] pixelData, int cols, int rows,
            int bitsAllocated, int samplesPerPixel, String photometric, Attributes attrs) {

        BufferedImage image;

        try {
            if (samplesPerPixel == 1) {
                // Grayscale image
                if (bitsAllocated == 8) {
                    image = new BufferedImage(cols, rows, BufferedImage.TYPE_BYTE_GRAY);
                    byte[] imgData = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
                    System.arraycopy(pixelData, 0, imgData, 0, Math.min(pixelData.length, imgData.length));
                } else if (bitsAllocated == 16) {
                    // 16-bit grayscale - need to window/level for display
                    image = new BufferedImage(cols, rows, BufferedImage.TYPE_BYTE_GRAY);
                    byte[] imgData = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();

                    // Get window/level if available
                    double windowCenter = attrs.getDouble(Tag.WindowCenter, 0, 32768);
                    double windowWidth = attrs.getDouble(Tag.WindowWidth, 0, 65536);

                    double minVal = windowCenter - windowWidth / 2;
                    double maxVal = windowCenter + windowWidth / 2;
                    double scale = 255.0 / (maxVal - minVal);

                    // Convert 16-bit to 8-bit with windowing
                    for (int i = 0; i < cols * rows && i * 2 + 1 < pixelData.length; i++) {
                        int val = ((pixelData[i * 2 + 1] & 0xFF) << 8) | (pixelData[i * 2] & 0xFF);
                        double scaled = (val - minVal) * scale;
                        imgData[i] = (byte) Math.min(255, Math.max(0, (int) scaled));
                    }
                } else {
                    log.warn("Unsupported bits allocated: {}", bitsAllocated);
                    return null;
                }

                // Handle MONOCHROME1 (inverted)
                if ("MONOCHROME1".equals(photometric)) {
                    byte[] imgData = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
                    for (int i = 0; i < imgData.length; i++) {
                        imgData[i] = (byte) (255 - (imgData[i] & 0xFF));
                    }
                }

            } else if (samplesPerPixel == 3) {
                // RGB image
                image = new BufferedImage(cols, rows, BufferedImage.TYPE_3BYTE_BGR);
                byte[] imgData = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();

                // DICOM RGB may be planar or interleaved
                int planarConfig = attrs.getInt(Tag.PlanarConfiguration, 0);

                if (planarConfig == 0) {
                    // Interleaved: R1G1B1R2G2B2...
                    // BufferedImage expects BGR, so swap
                    for (int i = 0; i < cols * rows && i * 3 + 2 < pixelData.length; i++) {
                        imgData[i * 3] = pixelData[i * 3 + 2];     // B
                        imgData[i * 3 + 1] = pixelData[i * 3 + 1]; // G
                        imgData[i * 3 + 2] = pixelData[i * 3];     // R
                    }
                } else {
                    // Planar: R1R2...G1G2...B1B2...
                    int planeSize = cols * rows;
                    for (int i = 0; i < planeSize && i < imgData.length / 3; i++) {
                        imgData[i * 3] = pixelData[planeSize * 2 + i];   // B
                        imgData[i * 3 + 1] = pixelData[planeSize + i];   // G
                        imgData[i * 3 + 2] = pixelData[i];               // R
                    }
                }
            } else {
                log.warn("Unsupported samples per pixel: {}", samplesPerPixel);
                return null;
            }

            return image;

        } catch (Exception e) {
            log.error("Error converting pixel data: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Black out a rectangular region in the pixel data.
     */
    private void blackoutRegion(byte[] pixelData, int cols, int rows,
            int bitsAllocated, int samplesPerPixel, int x, int y, int w, int h) {

        int bytesPerPixel = (bitsAllocated / 8) * samplesPerPixel;
        int rowBytes = cols * bytesPerPixel;

        for (int row = y; row < y + h && row < rows; row++) {
            for (int col = x; col < x + w && col < cols; col++) {
                int offset = row * rowBytes + col * bytesPerPixel;

                // Set all bytes for this pixel to 0 (black)
                for (int b = 0; b < bytesPerPixel && offset + b < pixelData.length; b++) {
                    pixelData[offset + b] = 0;
                }
            }
        }
    }

    /**
     * Result class for OCR processing.
     */
    public static class OcrResult {
        private String sourceName;
        private int imageWidth;
        private int imageHeight;
        private List<DetectedRegion> detectedRegions = new ArrayList<>();
        private int phiRegionCount;
        private String alterPixelScript;
        private String error;

        public String getSourceName() { return sourceName; }
        public void setSourceName(String sourceName) { this.sourceName = sourceName; }

        public int getImageWidth() { return imageWidth; }
        public void setImageWidth(int imageWidth) { this.imageWidth = imageWidth; }

        public int getImageHeight() { return imageHeight; }
        public void setImageHeight(int imageHeight) { this.imageHeight = imageHeight; }

        public List<DetectedRegion> getDetectedRegions() { return detectedRegions; }
        public void setDetectedRegions(List<DetectedRegion> detectedRegions) {
            this.detectedRegions = detectedRegions;
        }

        public int getPhiRegionCount() { return phiRegionCount; }
        public void setPhiRegionCount(int phiRegionCount) { this.phiRegionCount = phiRegionCount; }

        public String getAlterPixelScript() { return alterPixelScript; }
        public void setAlterPixelScript(String alterPixelScript) { this.alterPixelScript = alterPixelScript; }

        public String getError() { return error; }
        public void setError(String error) { this.error = error; }

        public boolean hasError() { return error != null && !error.isEmpty(); }
        public boolean hasPhiDetected() { return phiRegionCount > 0; }
    }
}
