/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.review;

import io.xnatworks.router.archive.ArchiveManager;
import io.xnatworks.router.broker.CrosswalkStore;
import io.xnatworks.router.ocr.DetectedRegion;
import io.xnatworks.router.ocr.OcrService;
import io.xnatworks.router.review.model.*;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.ElementDictionary;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.dcm4che3.imageio.plugins.dcm.DicomImageReadParam;
import org.dcm4che3.io.DicomInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Service for comparing original and anonymized DICOM files.
 * Provides methods to generate study comparisons, header diffs,
 * and render images with OCR overlay.
 */
public class DicomComparisonService {
    private static final Logger log = LoggerFactory.getLogger(DicomComparisonService.class);

    // PHI tags that should be highlighted
    private static final Set<Integer> PHI_TAGS = new HashSet<>(Arrays.asList(
            Tag.PatientName,
            Tag.PatientID,
            Tag.PatientBirthDate,
            Tag.PatientSex,
            Tag.PatientAge,
            Tag.PatientWeight,
            Tag.PatientAddress,
            Tag.PatientTelephoneNumbers,
            Tag.OtherPatientIDs,
            Tag.OtherPatientNames,
            Tag.EthnicGroup,
            Tag.PatientComments,
            Tag.ReferringPhysicianName,
            Tag.PerformingPhysicianName,
            Tag.OperatorsName,
            Tag.InstitutionName,
            Tag.InstitutionAddress,
            Tag.InstitutionalDepartmentName,
            Tag.StationName,
            Tag.AccessionNumber,
            Tag.StudyID,
            Tag.StudyDescription,
            Tag.SeriesDescription,
            Tag.RequestingPhysician,
            Tag.ScheduledPerformingPhysicianName,
            Tag.NameOfPhysiciansReadingStudy,
            Tag.MedicalRecordLocator,
            Tag.ContentCreatorName,
            Tag.VerifyingObserverName,
            Tag.PersonName
    ));

    private final ArchiveManager archiveManager;
    private final OcrService ocrService;
    private final CrosswalkStore crosswalkStore;

    public DicomComparisonService(ArchiveManager archiveManager, OcrService ocrService, CrosswalkStore crosswalkStore) {
        this.archiveManager = archiveManager;
        this.ocrService = ocrService;
        this.crosswalkStore = crosswalkStore;
    }

    /**
     * Get a study comparison with metadata.
     *
     * @param aeTitle  The AE title (route)
     * @param studyUid The study UID
     * @return StudyComparison with metadata about original and anonymized versions
     */
    public StudyComparison getStudyComparison(String aeTitle, String studyUid) throws IOException {
        log.debug("[{}] Getting study comparison for {}", aeTitle, studyUid);

        ArchiveManager.ArchivedStudy archivedStudy = archiveManager.getArchivedStudy(aeTitle, studyUid);
        if (archivedStudy == null) {
            throw new IOException("Archived study not found: " + studyUid);
        }

        StudyComparison comparison = new StudyComparison();
        comparison.setStudyUid(studyUid);
        comparison.setAeTitle(aeTitle);

        // Set paths
        if (archivedStudy.getOriginalPath() != null) {
            comparison.setOriginalPath(archivedStudy.getOriginalPath().toString());
        }
        if (archivedStudy.getAnonymizedPath() != null) {
            comparison.setAnonymizedPath(archivedStudy.getAnonymizedPath().toString());
        }

        // Get file counts
        int originalCount = archivedStudy.getOriginalFiles() != null ? archivedStudy.getOriginalFiles().size() : 0;
        int anonCount = archivedStudy.getAnonymizedFiles() != null ? archivedStudy.getAnonymizedFiles().size() : 0;
        comparison.setFileCount(Math.max(originalCount, anonCount));

        // Read metadata from first original file
        if (archivedStudy.getOriginalFiles() != null && !archivedStudy.getOriginalFiles().isEmpty()) {
            Path firstFile = archivedStudy.getOriginalFiles().get(0);
            try (DicomInputStream dis = new DicomInputStream(firstFile.toFile())) {
                Attributes attrs = dis.readDataset();
                comparison.setPatientId(attrs.getString(Tag.PatientID, ""));
                comparison.setPatientName(attrs.getString(Tag.PatientName, ""));
                comparison.setStudyDate(attrs.getString(Tag.StudyDate, ""));
                comparison.setStudyDescription(attrs.getString(Tag.StudyDescription, ""));
                comparison.setAccessionNumber(attrs.getString(Tag.AccessionNumber, ""));
            } catch (IOException e) {
                log.warn("Failed to read metadata from {}: {}", firstFile, e.getMessage());
            }
        }

        // Get scan count (count unique series UIDs)
        Set<String> seriesUids = new HashSet<>();
        if (archivedStudy.getOriginalFiles() != null) {
            for (Path file : archivedStudy.getOriginalFiles()) {
                try (DicomInputStream dis = new DicomInputStream(file.toFile())) {
                    Attributes attrs = dis.readDataset();
                    String seriesUid = attrs.getString(Tag.SeriesInstanceUID);
                    if (seriesUid != null) {
                        seriesUids.add(seriesUid);
                    }
                } catch (IOException e) {
                    log.debug("Failed to read series UID from {}: {}", file, e.getMessage());
                }
            }
        }
        comparison.setScanCount(seriesUids.size());

        // Get metadata from archive
        if (archivedStudy.getMetadata() != null) {
            comparison.setScriptUsed(archivedStudy.getMetadata().getAnonymizationScript());
            comparison.setPhiFieldsModified(archivedStudy.getMetadata().getPhiFieldsModified());
        }

        return comparison;
    }

    /**
     * Get scan comparisons for a study.
     *
     * @param aeTitle  The AE title (route)
     * @param studyUid The study UID
     * @return List of ScanComparison for each series
     */
    public List<ScanComparison> getScanComparisons(String aeTitle, String studyUid) throws IOException {
        log.debug("[{}] Getting scan comparisons for {}", aeTitle, studyUid);

        ArchiveManager.ArchivedStudy archivedStudy = archiveManager.getArchivedStudy(aeTitle, studyUid);
        if (archivedStudy == null) {
            throw new IOException("Archived study not found: " + studyUid);
        }

        // Get metadata for broker info
        ArchiveManager.ArchiveMetadata metadata = archivedStudy.getMetadata();
        String brokerName = metadata != null ? metadata.getHonestBrokerName() : null;
        boolean hashUidsEnabled = metadata != null && metadata.isHashUidsEnabled();

        // Group original files by series UID
        Map<String, List<DicomFileInfo>> originalBySeries = groupFilesBySeries(archivedStudy.getOriginalFiles());
        Map<String, List<DicomFileInfo>> anonBySeries = groupFilesBySeries(archivedStudy.getAnonymizedFiles());

        // Create mapping from SOP UID (internal in file) to anonymized file path
        Map<String, Path> sopToAnonFile = new HashMap<>();
        if (archivedStudy.getAnonymizedFiles() != null) {
            for (Path file : archivedStudy.getAnonymizedFiles()) {
                try (DicomInputStream dis = new DicomInputStream(file.toFile())) {
                    Attributes attrs = dis.readDataset();
                    String sopUid = attrs.getString(Tag.SOPInstanceUID);
                    if (sopUid != null) {
                        sopToAnonFile.put(sopUid, file);
                    }
                } catch (IOException e) {
                    log.debug("Failed to read SOP UID from {}: {}", file, e.getMessage());
                }
            }
        }

        // Build scan comparisons
        List<ScanComparison> scans = new ArrayList<>();
        for (Map.Entry<String, List<DicomFileInfo>> entry : originalBySeries.entrySet()) {
            String seriesUid = entry.getKey();
            List<DicomFileInfo> originalFiles = entry.getValue();

            ScanComparison scan = new ScanComparison();
            scan.setSeriesUid(seriesUid);

            // Get series info from first file
            if (!originalFiles.isEmpty()) {
                DicomFileInfo first = originalFiles.get(0);
                scan.setSeriesDescription(first.seriesDescription);
                scan.setModality(first.modality);
                scan.setSeriesNumber(first.seriesNumber);
            }

            // Match files by instance number
            originalFiles.sort(Comparator.comparingInt(f -> f.instanceNumber));

            for (DicomFileInfo origInfo : originalFiles) {
                FileComparison fc = new FileComparison();
                fc.setSopInstanceUid(origInfo.sopUid);
                fc.setOriginalFile(origInfo.path.toString());
                fc.setInstanceNumber(origInfo.instanceNumber);

                // Find matching anonymized file using multiple strategies

                // Strategy 1: Use crosswalk to look up hashed SOP UID (most accurate for hashUids=true)
                if (fc.getAnonymizedFile() == null && hashUidsEnabled && brokerName != null && crosswalkStore != null) {
                    String hashedSopUid = crosswalkStore.lookup(brokerName, origInfo.sopUid, CrosswalkStore.ID_TYPE_SOP_UID);
                    if (hashedSopUid != null) {
                        Path anonFile = sopToAnonFile.get(hashedSopUid);
                        if (anonFile != null) {
                            fc.setAnonymizedFile(anonFile.toString());
                            log.debug("Matched via crosswalk: {} -> {}", origInfo.sopUid, hashedSopUid);
                        }
                    }
                }

                // Strategy 2: Match by filename (works when files are named by original SOP UID)
                if (fc.getAnonymizedFile() == null) {
                    String origFilename = origInfo.path.getFileName().toString();
                    Path anonymizedDir = archivedStudy.getAnonymizedPath();
                    if (anonymizedDir != null) {
                        Path matchingAnonFile = anonymizedDir.resolve(origFilename);
                        if (Files.exists(matchingAnonFile)) {
                            fc.setAnonymizedFile(matchingAnonFile.toString());
                        }
                    }
                }

                // Strategy 3: Match by internal SOP UID (for non-hashed UIDs)
                if (fc.getAnonymizedFile() == null) {
                    Path anonFile = sopToAnonFile.get(origInfo.sopUid);
                    if (anonFile != null) {
                        fc.setAnonymizedFile(anonFile.toString());
                    }
                }

                // Strategy 4: Fallback to instance number matching (least reliable)
                if (fc.getAnonymizedFile() == null) {
                    List<DicomFileInfo> anonSeriesFiles = anonBySeries.getOrDefault(seriesUid, Collections.emptyList());
                    for (DicomFileInfo anonInfo : anonSeriesFiles) {
                        if (anonInfo.instanceNumber == origInfo.instanceNumber) {
                            fc.setAnonymizedFile(anonInfo.path.toString());
                            break;
                        }
                    }
                }

                scan.addFile(fc);
            }

            scans.add(scan);
        }

        // Sort by series number
        scans.sort(Comparator.comparingInt(ScanComparison::getSeriesNumber));

        return scans;
    }

    /**
     * Compare headers between an original and anonymized file.
     *
     * @param originalPath   Path to original file
     * @param anonymizedPath Path to anonymized file
     * @return HeaderComparison with all tag differences
     */
    public HeaderComparison compareHeaders(Path originalPath, Path anonymizedPath) throws IOException {
        log.debug("Comparing headers: {} vs {}", originalPath, anonymizedPath);

        HeaderComparison comparison = new HeaderComparison();
        comparison.setOriginalFile(originalPath.toString());
        comparison.setAnonymizedFile(anonymizedPath.toString());

        Attributes origAttrs = null;
        Attributes anonAttrs = null;

        if (Files.exists(originalPath)) {
            try (DicomInputStream dis = new DicomInputStream(originalPath.toFile())) {
                origAttrs = dis.readDataset();
            }
        }

        if (Files.exists(anonymizedPath)) {
            try (DicomInputStream dis = new DicomInputStream(anonymizedPath.toFile())) {
                anonAttrs = dis.readDataset();
            }
        }

        if (origAttrs == null && anonAttrs == null) {
            return comparison;
        }

        // Collect all tags from both files
        Set<Integer> allTags = new TreeSet<>();
        if (origAttrs != null) {
            for (int tag : origAttrs.tags()) {
                allTags.add(tag);
            }
        }
        if (anonAttrs != null) {
            for (int tag : anonAttrs.tags()) {
                allTags.add(tag);
            }
        }

        // Compare each tag
        for (int tag : allTags) {
            if (tag == Tag.PixelData) {
                continue; // Skip pixel data
            }

            String tagHex = String.format("(%04X,%04X)", (tag >> 16) & 0xFFFF, tag & 0xFFFF);
            String tagName = ElementDictionary.keywordOf(tag, null);
            if (tagName == null || tagName.isEmpty()) {
                tagName = "Unknown";
            }

            VR vr = origAttrs != null ? origAttrs.getVR(tag) : (anonAttrs != null ? anonAttrs.getVR(tag) : null);
            String vrStr = vr != null ? vr.name() : "UN";

            String origValue = origAttrs != null ? getTagValue(origAttrs, tag) : null;
            String anonValue = anonAttrs != null ? getTagValue(anonAttrs, tag) : null;

            boolean removed = origValue != null && anonValue == null;
            boolean added = origValue == null && anonValue != null;
            boolean changed = !removed && !added && !Objects.equals(origValue, anonValue);

            TagComparison tc = new TagComparison();
            tc.setTag(tagHex);
            tc.setName(tagName);
            tc.setVr(vrStr);
            tc.setCategory(getTagCategory(tag));
            tc.setOriginalValue(origValue != null ? origValue : "");
            tc.setAnonymizedValue(anonValue != null ? anonValue : "");
            tc.setChanged(changed);
            tc.setRemoved(removed);
            tc.setAdded(added);
            tc.setPhi(PHI_TAGS.contains(tag));

            comparison.addTag(tc);
        }

        comparison.computeStats();
        return comparison;
    }

    /**
     * Render a DICOM image as PNG with optional OCR overlay.
     *
     * @param dicomPath   Path to DICOM file
     * @param withOverlay Whether to include OCR bounding box overlay
     * @param frame       Frame number (for multi-frame images)
     * @return PNG image bytes
     */
    public byte[] renderImage(Path dicomPath, boolean withOverlay, int frame) throws IOException {
        log.debug("Rendering image: {} (overlay={}, frame={})", dicomPath, withOverlay, frame);

        Iterator<ImageReader> iter = ImageIO.getImageReadersByFormatName("DICOM");
        if (!iter.hasNext()) {
            throw new IOException("DICOM ImageReader not available");
        }

        ImageReader reader = iter.next();
        try (ImageInputStream iis = ImageIO.createImageInputStream(dicomPath.toFile())) {
            reader.setInput(iis);

            DicomImageReadParam param = (DicomImageReadParam) reader.getDefaultReadParam();
            BufferedImage image = reader.read(frame, param);

            if (image == null) {
                throw new IOException("Could not read image from DICOM file");
            }

            // Apply OCR overlay if requested
            if (withOverlay && ocrService != null) {
                image = applyOcrOverlay(image, dicomPath);
            }

            // Convert to PNG
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", baos);
            return baos.toByteArray();
        } finally {
            reader.dispose();
        }
    }

    /**
     * Get OCR detected regions for a DICOM file.
     *
     * @param dicomPath Path to DICOM file
     * @return List of detected regions with bounding boxes
     */
    public List<DetectedRegion> getOcrRegions(Path dicomPath) throws IOException {
        if (ocrService == null) {
            return Collections.emptyList();
        }

        // Read the image first
        Iterator<ImageReader> iter = ImageIO.getImageReadersByFormatName("DICOM");
        if (!iter.hasNext()) {
            return Collections.emptyList();
        }

        ImageReader reader = iter.next();
        try (ImageInputStream iis = ImageIO.createImageInputStream(dicomPath.toFile())) {
            reader.setInput(iis);

            DicomImageReadParam param = (DicomImageReadParam) reader.getDefaultReadParam();
            BufferedImage image = reader.read(0, param);

            if (image == null) {
                return Collections.emptyList();
            }

            return ocrService.detectText(image);
        } finally {
            reader.dispose();
        }
    }

    // Private helper methods

    private Map<String, List<DicomFileInfo>> groupFilesBySeries(List<Path> files) {
        Map<String, List<DicomFileInfo>> result = new LinkedHashMap<>();
        if (files == null) {
            return result;
        }

        for (Path file : files) {
            try (DicomInputStream dis = new DicomInputStream(file.toFile())) {
                Attributes attrs = dis.readDataset();
                String seriesUid = attrs.getString(Tag.SeriesInstanceUID, "unknown");

                DicomFileInfo info = new DicomFileInfo();
                info.path = file;
                info.sopUid = attrs.getString(Tag.SOPInstanceUID, "");
                info.seriesUid = seriesUid;
                info.seriesDescription = attrs.getString(Tag.SeriesDescription, "");
                info.modality = attrs.getString(Tag.Modality, "");
                info.instanceNumber = attrs.getInt(Tag.InstanceNumber, 0);
                info.seriesNumber = attrs.getInt(Tag.SeriesNumber, 0);

                result.computeIfAbsent(seriesUid, k -> new ArrayList<>()).add(info);
            } catch (IOException e) {
                log.debug("Failed to read DICOM file {}: {}", file, e.getMessage());
            }
        }

        return result;
    }

    private String getTagValue(Attributes attrs, int tag) {
        Object value = attrs.getValue(tag);
        if (value == null) {
            return null;
        }

        // Try to get string representation first - dcm4che handles VR-specific conversions
        // This works for UI (UIDs), LO, SH, PN, DA, TM, CS, and other text-based VRs
        String strValue = attrs.getString(tag, null);
        if (strValue != null && !strValue.isEmpty()) {
            if (strValue.length() > 200) {
                return strValue.substring(0, 200) + "...";
            }
            return strValue;
        }

        // For multi-valued attributes, try to get all values
        String[] values = attrs.getStrings(tag);
        if (values != null && values.length > 0) {
            String joined = String.join(" \\ ", values);
            if (joined.length() > 200) {
                return joined.substring(0, 200) + "...";
            }
            return joined;
        }

        // Fall back to binary display only for truly non-text data
        if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            // Try to interpret as ASCII/UTF-8 if reasonable size
            if (bytes.length > 0 && bytes.length <= 1000) {
                try {
                    String decoded = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim();
                    // Check if it's printable (not binary garbage)
                    if (decoded.chars().allMatch(c -> c >= 32 && c < 127 || c == '\n' || c == '\r' || c == '\t')) {
                        if (decoded.length() > 200) {
                            return decoded.substring(0, 200) + "...";
                        }
                        return decoded;
                    }
                } catch (Exception e) {
                    // Fall through to binary display
                }
            }
            return "[binary: " + bytes.length + " bytes]";
        }

        // Handle other types (sequences, etc.)
        return value.toString();
    }

    private String getTagCategory(int tag) {
        // Patient tags: 0010,xxxx
        if ((tag >> 16) == 0x0010) {
            return "Patient";
        }
        // Study tags: 0008,xxxx (many are study-level)
        if ((tag >> 16) == 0x0008) {
            if (tag == Tag.StudyDate || tag == Tag.StudyTime || tag == Tag.StudyInstanceUID ||
                    tag == Tag.StudyDescription || tag == Tag.StudyID || tag == Tag.AccessionNumber) {
                return "Study";
            }
            if (tag == Tag.SeriesDate || tag == Tag.SeriesTime || tag == Tag.SeriesInstanceUID ||
                    tag == Tag.SeriesDescription || tag == Tag.SeriesNumber || tag == Tag.Modality) {
                return "Series";
            }
            return "Study";
        }
        // Series tags: 0020,xxxx
        if ((tag >> 16) == 0x0020) {
            return "Series";
        }
        // Image pixel tags: 0028,xxxx and 7FE0,xxxx
        if ((tag >> 16) == 0x0028 || (tag >> 16) == 0x7FE0) {
            return "Image";
        }
        // Equipment tags: 0008,0070-0008,0090
        if (tag >= 0x00080070 && tag <= 0x00080090) {
            return "Equipment";
        }
        return "Other";
    }

    private BufferedImage applyOcrOverlay(BufferedImage image, Path dicomPath) {
        try {
            List<DetectedRegion> regions = ocrService.detectText(image);
            if (regions.isEmpty()) {
                return image;
            }

            // Create a copy to draw on
            BufferedImage result = new BufferedImage(
                    image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = result.createGraphics();
            g2d.drawImage(image, 0, 0, null);

            // Draw bounding boxes
            g2d.setStroke(new BasicStroke(2));
            for (DetectedRegion region : regions) {
                // Use red for PHI, yellow for other text
                if (region.isPhi()) {
                    g2d.setColor(new Color(255, 0, 0, 180));
                } else {
                    g2d.setColor(new Color(255, 255, 0, 180));
                }
                g2d.drawRect(region.getX(), region.getY(), region.getWidth(), region.getHeight());

                // Draw text label
                g2d.setFont(new Font("SansSerif", Font.BOLD, 10));
                g2d.setColor(region.isPhi() ? Color.RED : Color.YELLOW);
                String label = region.isPhi() ? "PHI: " + truncate(region.getText(), 15) : truncate(region.getText(), 15);
                g2d.drawString(label, region.getX(), region.getY() - 2);
            }

            g2d.dispose();
            return result;
        } catch (Exception e) {
            log.warn("Failed to apply OCR overlay: {}", e.getMessage());
            return image;
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    // Helper class for file info
    private static class DicomFileInfo {
        Path path;
        String sopUid;
        String seriesUid;
        String seriesDescription;
        String modality;
        int instanceNumber;
        int seriesNumber;
    }
}
