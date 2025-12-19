/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.anon;

import org.dcm4che2.data.DicomElement;
import org.dcm4che2.data.DicomObject;
import org.dcm4che2.data.SpecificCharacterSet;
import org.dcm4che2.data.Tag;
import org.dcm4che2.data.VR;
import org.dcm4che2.io.DicomInputStream;
import org.dcm4che2.util.TagUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Service for generating anonymization audit reports.
 * Compares original vs anonymized DICOM files and validates conformance to scripts.
 */
public class AnonymizationAuditService {
    private static final Logger log = LoggerFactory.getLogger(AnonymizationAuditService.class);

    // Known PHI tags to check for residual data
    private static final Map<Integer, String> PHI_TAGS = new LinkedHashMap<>();
    static {
        PHI_TAGS.put(Tag.PatientName, "Patient Name");
        PHI_TAGS.put(Tag.PatientID, "Patient ID");
        PHI_TAGS.put(Tag.PatientBirthDate, "Patient Birth Date");
        PHI_TAGS.put(Tag.PatientBirthTime, "Patient Birth Time");
        PHI_TAGS.put(Tag.PatientSex, "Patient Sex");
        PHI_TAGS.put(Tag.PatientAge, "Patient Age");
        PHI_TAGS.put(Tag.PatientSize, "Patient Size");
        PHI_TAGS.put(Tag.PatientWeight, "Patient Weight");
        PHI_TAGS.put(Tag.PatientAddress, "Patient Address");
        PHI_TAGS.put(Tag.OtherPatientIDs, "Other Patient IDs");
        PHI_TAGS.put(Tag.OtherPatientNames, "Other Patient Names");
        PHI_TAGS.put(Tag.PatientMotherBirthName, "Patient Mother Birth Name");
        PHI_TAGS.put(Tag.PatientTelephoneNumbers, "Patient Telephone Numbers");
        PHI_TAGS.put(Tag.EthnicGroup, "Ethnic Group");
        PHI_TAGS.put(Tag.StudyDate, "Study Date");
        PHI_TAGS.put(Tag.SeriesDate, "Series Date");
        PHI_TAGS.put(Tag.AcquisitionDate, "Acquisition Date");
        PHI_TAGS.put(Tag.ContentDate, "Content Date");
        PHI_TAGS.put(Tag.StudyTime, "Study Time");
        PHI_TAGS.put(Tag.SeriesTime, "Series Time");
        PHI_TAGS.put(Tag.AcquisitionTime, "Acquisition Time");
        PHI_TAGS.put(Tag.ContentTime, "Content Time");
        PHI_TAGS.put(Tag.AccessionNumber, "Accession Number");
        PHI_TAGS.put(Tag.InstitutionName, "Institution Name");
        PHI_TAGS.put(Tag.InstitutionAddress, "Institution Address");
        PHI_TAGS.put(Tag.ReferringPhysicianName, "Referring Physician Name");
        PHI_TAGS.put(Tag.ReferringPhysicianAddress, "Referring Physician Address");
        PHI_TAGS.put(Tag.ReferringPhysicianTelephoneNumbers, "Referring Physician Tel");
        PHI_TAGS.put(Tag.StationName, "Station Name");
        PHI_TAGS.put(Tag.InstitutionalDepartmentName, "Dept Name");
        PHI_TAGS.put(Tag.PhysiciansOfRecord, "Physicians of Record");
        PHI_TAGS.put(Tag.PerformingPhysicianName, "Performing Physician Name");
        PHI_TAGS.put(Tag.NameOfPhysiciansReadingStudy, "Reading Physician Name");
        PHI_TAGS.put(Tag.OperatorsName, "Operators Name");
        PHI_TAGS.put(Tag.StudyInstanceUID, "Study Instance UID");
        PHI_TAGS.put(Tag.SeriesInstanceUID, "Series Instance UID");
        PHI_TAGS.put(Tag.SOPInstanceUID, "SOP Instance UID");
    }

    private final ScriptLibrary scriptLibrary;

    public AnonymizationAuditService(ScriptLibrary scriptLibrary) {
        this.scriptLibrary = scriptLibrary;
    }

    /**
     * Generate an audit report comparing original and anonymized DICOM directories.
     *
     * @param originalDir     Directory with original DICOM files
     * @param anonymizedDir   Directory with anonymized DICOM files
     * @param scriptName      Name of the script that was applied
     * @return Audit report with comparison details
     */
    public AuditReport generateReport(Path originalDir, Path anonymizedDir, String scriptName) throws IOException {
        log.info("Generating audit report: original={}, anonymized={}, script={}",
                originalDir, anonymizedDir, scriptName);

        AuditReport report = new AuditReport();
        report.setReportId(UUID.randomUUID().toString());
        report.setGeneratedAt(LocalDateTime.now());
        report.setOriginalDirectory(originalDir.toString());
        report.setAnonymizedDirectory(anonymizedDir.toString());
        report.setScriptName(scriptName);

        // Load script content and parse expected operations
        String scriptContent = null;
        ScriptExpectations expectations = null;
        if (scriptName != null && !scriptName.equals("passthrough")) {
            try {
                scriptContent = scriptLibrary.getScriptContent(scriptName);
                report.setScriptContent(scriptContent);
                expectations = parseScriptExpectations(scriptContent);
            } catch (Exception e) {
                log.warn("Could not load script '{}': {}", scriptName, e.getMessage());
            }
        }

        // Find matching files in both directories
        List<FilePair> filePairs = findFilePairs(originalDir, anonymizedDir);
        report.setTotalFilesOriginal(countFiles(originalDir));
        report.setTotalFilesAnonymized(countFiles(anonymizedDir));
        report.setMatchedFiles(filePairs.size());

        // Analyze each file pair
        for (FilePair pair : filePairs) {
            try {
                FileComparison comparison = compareFiles(pair.original, pair.anonymized, expectations);
                report.getFileComparisons().add(comparison);

                // Aggregate changed tags
                for (TagChange change : comparison.getChanges()) {
                    report.aggregateTagChange(change.getTagHex(), change.getTagName());
                }

                // Check conformance
                if (!comparison.getConformanceIssues().isEmpty()) {
                    report.incrementNonConformant();
                }
            } catch (Exception e) {
                log.error("Error comparing files {} vs {}: {}",
                        pair.original.getFileName(), pair.anonymized.getFileName(), e.getMessage());
                report.addError("Failed to compare " + pair.original.getFileName() + ": " + e.getMessage());
            }
        }

        // Calculate summary statistics
        report.calculateSummary();

        log.info("Audit report generated: {} files compared, {} conformance issues",
                report.getMatchedFiles(), report.getNonConformantFiles());

        return report;
    }

    /**
     * Generate a simplified audit for a single original/anonymized file pair.
     */
    public FileComparison compareFiles(Path originalFile, Path anonymizedFile, String scriptName) throws IOException {
        ScriptExpectations expectations = null;
        if (scriptName != null && !scriptName.equals("passthrough")) {
            try {
                String scriptContent = scriptLibrary.getScriptContent(scriptName);
                expectations = parseScriptExpectations(scriptContent);
            } catch (Exception e) {
                log.warn("Could not load script '{}': {}", scriptName, e.getMessage());
            }
        }
        return compareFiles(originalFile, anonymizedFile, expectations);
    }

    /**
     * Compare two DICOM files and identify changes.
     */
    private FileComparison compareFiles(Path originalFile, Path anonymizedFile,
                                         ScriptExpectations expectations) throws IOException {
        FileComparison comparison = new FileComparison();
        comparison.setOriginalFile(originalFile.getFileName().toString());
        comparison.setAnonymizedFile(anonymizedFile.getFileName().toString());

        // Read both DICOM files
        DicomObject original;
        DicomObject anonymized;

        try (DicomInputStream dis = new DicomInputStream(originalFile.toFile())) {
            original = dis.readDicomObject();
        }

        try (DicomInputStream dis = new DicomInputStream(anonymizedFile.toFile())) {
            anonymized = dis.readDicomObject();
        }

        SpecificCharacterSet cs = original.getSpecificCharacterSet();

        // Compare all tags that exist in either file
        Set<Integer> allTags = new TreeSet<>();
        collectTags(original, allTags);
        collectTags(anonymized, allTags);

        for (int tag : allTags) {
            String originalValue = getTagValue(original, tag, cs);
            String anonymizedValue = getTagValue(anonymized, tag, cs);

            // Check if value changed
            if (!Objects.equals(originalValue, anonymizedValue)) {
                TagChange change = new TagChange();
                change.setTagHex(TagUtils.toString(tag));
                change.setTagName(getTagName(tag));
                change.setOriginalValue(truncateValue(originalValue));
                change.setAnonymizedValue(truncateValue(anonymizedValue));
                change.setPhi(PHI_TAGS.containsKey(tag));

                // Determine action taken
                if (originalValue != null && (anonymizedValue == null || anonymizedValue.isEmpty())) {
                    change.setAction("removed");
                } else if ((originalValue == null || originalValue.isEmpty()) && anonymizedValue != null) {
                    change.setAction("added");
                } else if (isHashedValue(originalValue, anonymizedValue)) {
                    change.setAction("hashed");
                } else {
                    change.setAction("replaced");
                }

                comparison.getChanges().add(change);
            }
        }

        // Check conformance against script expectations
        if (expectations != null) {
            validateConformance(original, anonymized, cs, expectations, comparison);
        }

        // Check for residual PHI
        checkResidualPhi(anonymized, cs, comparison);

        // Check de-identification markers
        checkDeidentificationMarkers(anonymized, cs, comparison);

        return comparison;
    }

    /**
     * Parse script to extract expected tag operations.
     */
    private ScriptExpectations parseScriptExpectations(String scriptContent) {
        ScriptExpectations expectations = new ScriptExpectations();

        // Pattern for tag operations: (XXXX,XXXX) := value or (XXXX,XXXX) keep
        Pattern tagPattern = Pattern.compile(
                "\\(([0-9A-Fa-f]{4}),([0-9A-Fa-f]{4})\\)\\s*(:=\\s*(.+)|keep)",
                Pattern.CASE_INSENSITIVE);

        for (String line : scriptContent.split("\n")) {
            String trimmed = line.trim();

            // Skip comments and empty lines
            if (trimmed.isEmpty() || trimmed.startsWith("//")) {
                continue;
            }

            Matcher matcher = tagPattern.matcher(trimmed);
            if (matcher.find()) {
                int group = Integer.parseInt(matcher.group(1), 16);
                int element = Integer.parseInt(matcher.group(2), 16);
                int tag = (group << 16) | element;

                if (matcher.group(3).toLowerCase().startsWith("keep")) {
                    expectations.getKeptTags().add(tag);
                } else {
                    String value = matcher.group(4);
                    if (value != null) {
                        value = value.replaceAll("//.*$", "").trim(); // Remove inline comments
                        // Check for hash function
                        if (value.contains("hashUID") || value.contains("hash(")) {
                            expectations.getHashedTags().add(tag);
                        } else if (value.equals("\"\"") || value.isEmpty()) {
                            expectations.getRemovedTags().add(tag);
                        } else {
                            expectations.getReplacedTags().put(tag, value.replaceAll("\"", ""));
                        }
                    }
                }
            }
        }

        return expectations;
    }

    /**
     * Validate that anonymization conforms to script expectations.
     */
    private void validateConformance(DicomObject original, DicomObject anonymized,
                                      SpecificCharacterSet cs, ScriptExpectations expectations,
                                      FileComparison comparison) {
        // Check removed tags (should be empty or missing)
        for (int tag : expectations.getRemovedTags()) {
            String anonValue = getTagValue(anonymized, tag, cs);
            if (anonValue != null && !anonValue.isEmpty()) {
                comparison.addConformanceIssue(
                        String.format("Tag %s (%s) should be removed but contains: %s",
                                TagUtils.toString(tag), getTagName(tag), truncateValue(anonValue)));
            }
        }

        // Check replaced tags (should match expected value)
        for (Map.Entry<Integer, String> entry : expectations.getReplacedTags().entrySet()) {
            int tag = entry.getKey();
            String expected = entry.getValue();
            String actual = getTagValue(anonymized, tag, cs);

            if (actual == null || !actual.equals(expected)) {
                comparison.addConformanceIssue(
                        String.format("Tag %s (%s) expected '%s' but got '%s'",
                                TagUtils.toString(tag), getTagName(tag), expected, truncateValue(actual)));
            }
        }

        // Check kept tags (should match original)
        for (int tag : expectations.getKeptTags()) {
            String origValue = getTagValue(original, tag, cs);
            String anonValue = getTagValue(anonymized, tag, cs);

            if (!Objects.equals(origValue, anonValue)) {
                comparison.addConformanceIssue(
                        String.format("Tag %s (%s) should be kept unchanged but was modified",
                                TagUtils.toString(tag), getTagName(tag)));
            }
        }

        // Check hashed tags (should be different from original but present)
        for (int tag : expectations.getHashedTags()) {
            String origValue = getTagValue(original, tag, cs);
            String anonValue = getTagValue(anonymized, tag, cs);

            if (anonValue == null || anonValue.isEmpty()) {
                comparison.addConformanceIssue(
                        String.format("Tag %s (%s) should be hashed but is missing",
                                TagUtils.toString(tag), getTagName(tag)));
            } else if (origValue != null && origValue.equals(anonValue)) {
                comparison.addConformanceIssue(
                        String.format("Tag %s (%s) should be hashed but was not changed",
                                TagUtils.toString(tag), getTagName(tag)));
            }
        }
    }

    /**
     * Check for any residual PHI in anonymized file.
     */
    private void checkResidualPhi(DicomObject anonymized, SpecificCharacterSet cs,
                                   FileComparison comparison) {
        for (Map.Entry<Integer, String> entry : PHI_TAGS.entrySet()) {
            int tag = entry.getKey();
            String tagName = entry.getValue();
            String value = getTagValue(anonymized, tag, cs);

            if (value != null && !value.isEmpty()) {
                // Check for suspicious patterns in patient name
                if (tag == Tag.PatientName) {
                    if (!value.equalsIgnoreCase("ANONYMOUS") &&
                        !value.equalsIgnoreCase("RESEARCH_SUBJECT") &&
                        !value.equalsIgnoreCase("UNKNOWN") &&
                        !value.matches("^[A-F0-9-]+$") && // Not a hash
                        !value.matches("^SUBJ[-_]?[A-Z0-9]+$")) { // Not a subject ID format
                        comparison.addResidualPhiWarning(
                                String.format("%s may contain real name: %s", tagName, truncateValue(value)));
                    }
                }
                // Check for real dates (not empty or clearly anonymized)
                else if (tagName.contains("Date")) {
                    if (value.length() == 8 && value.matches("\\d{8}")) {
                        // Could be a real date - flag for review
                        comparison.addResidualPhiWarning(
                                String.format("%s contains date value: %s (verify if intended)",
                                        tagName, value));
                    }
                }
            }
        }
    }

    /**
     * Check for proper de-identification markers.
     */
    private void checkDeidentificationMarkers(DicomObject anonymized, SpecificCharacterSet cs,
                                               FileComparison comparison) {
        // Check Patient Identity Removed (0012,0062)
        String identityRemoved = getTagValue(anonymized, Tag.PatientIdentityRemoved, cs);
        if (identityRemoved == null || !identityRemoved.equalsIgnoreCase("YES")) {
            comparison.addConformanceIssue(
                    "De-identification marker (0012,0062) Patient Identity Removed should be 'YES'");
        }

        // Check De-identification Method (0012,0063)
        String deidentMethod = getTagValue(anonymized, Tag.DeidentificationMethod, cs);
        if (deidentMethod == null || deidentMethod.isEmpty()) {
            comparison.addConformanceIssue(
                    "De-identification marker (0012,0063) De-identification Method should be set");
        }
    }

    /**
     * Find matching file pairs between original and anonymized directories.
     */
    private List<FilePair> findFilePairs(Path originalDir, Path anonymizedDir) throws IOException {
        List<FilePair> pairs = new ArrayList<>();

        // Get all files in anonymized dir
        Map<String, Path> anonFiles = new HashMap<>();
        try (Stream<Path> stream = Files.walk(anonymizedDir)) {
            stream.filter(Files::isRegularFile)
                  .filter(this::isDicomFile)
                  .forEach(p -> anonFiles.put(p.getFileName().toString(), p));
        }

        // Match with original files
        try (Stream<Path> stream = Files.walk(originalDir)) {
            stream.filter(Files::isRegularFile)
                  .filter(this::isDicomFile)
                  .forEach(origFile -> {
                      Path anonFile = anonFiles.get(origFile.getFileName().toString());
                      if (anonFile != null) {
                          pairs.add(new FilePair(origFile, anonFile));
                      }
                  });
        }

        return pairs;
    }

    private boolean isDicomFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".dcm")) return true;
        if (!name.contains(".")) return true;
        if (name.matches("\\d+")) return true;
        return false;
    }

    private int countFiles(Path dir) throws IOException {
        try (Stream<Path> stream = Files.walk(dir)) {
            return (int) stream.filter(Files::isRegularFile).filter(this::isDicomFile).count();
        }
    }

    private void collectTags(DicomObject obj, Set<Integer> tags) {
        Iterator<DicomElement> iter = obj.iterator();
        while (iter.hasNext()) {
            DicomElement elem = iter.next();
            tags.add(elem.tag());
        }
    }

    private String getTagValue(DicomObject obj, int tag, SpecificCharacterSet cs) {
        DicomElement elem = obj.get(tag);
        if (elem == null) return null;

        try {
            return elem.getString(cs, false);
        } catch (Exception e) {
            return null;
        }
    }

    private String getTagName(int tag) {
        // Check our PHI map first
        String name = PHI_TAGS.get(tag);
        if (name != null) return name;

        // Return hex tag if name unknown
        return TagUtils.toString(tag);
    }

    private String truncateValue(String value) {
        if (value == null) return null;
        if (value.length() > 50) {
            return value.substring(0, 47) + "...";
        }
        return value;
    }

    private boolean isHashedValue(String original, String anonymized) {
        if (original == null || anonymized == null) return false;
        // Check if anonymized value looks like a hash (all hex, similar length to UID)
        if (anonymized.matches("^[0-9A-Fa-f.-]+$") && anonymized.length() >= 20) {
            return true;
        }
        // Check if it's a modified UID with different prefix
        if (original.startsWith("1.") && anonymized.startsWith("2.25.")) {
            return true;
        }
        return false;
    }

    // Inner classes for data structures

    private static class FilePair {
        final Path original;
        final Path anonymized;

        FilePair(Path original, Path anonymized) {
            this.original = original;
            this.anonymized = anonymized;
        }
    }

    private static class ScriptExpectations {
        private Set<Integer> removedTags = new HashSet<>();
        private Set<Integer> keptTags = new HashSet<>();
        private Set<Integer> hashedTags = new HashSet<>();
        private Map<Integer, String> replacedTags = new HashMap<>();

        public Set<Integer> getRemovedTags() { return removedTags; }
        public Set<Integer> getKeptTags() { return keptTags; }
        public Set<Integer> getHashedTags() { return hashedTags; }
        public Map<Integer, String> getReplacedTags() { return replacedTags; }
    }

    // Public data classes for reports

    public static class AuditReport {
        private String reportId;
        private LocalDateTime generatedAt;
        private String originalDirectory;
        private String anonymizedDirectory;
        private String scriptName;
        private String scriptContent;
        private int totalFilesOriginal;
        private int totalFilesAnonymized;
        private int matchedFiles;
        private int nonConformantFiles;
        private Map<String, TagSummary> tagSummary = new LinkedHashMap<>();
        private List<FileComparison> fileComparisons = new ArrayList<>();
        private List<String> errors = new ArrayList<>();
        private boolean fullyConformant;
        private int totalChanges;
        private int phiFieldsModified;

        // Aggregation methods
        public void aggregateTagChange(String tagHex, String tagName) {
            TagSummary summary = tagSummary.computeIfAbsent(tagHex,
                    k -> new TagSummary(tagHex, tagName));
            summary.incrementCount();
        }

        public void incrementNonConformant() {
            nonConformantFiles++;
        }

        public void addError(String error) {
            errors.add(error);
        }

        public void calculateSummary() {
            totalChanges = 0;
            phiFieldsModified = 0;

            for (FileComparison fc : fileComparisons) {
                totalChanges += fc.getChanges().size();
                for (TagChange change : fc.getChanges()) {
                    if (change.isPhi()) {
                        phiFieldsModified++;
                    }
                }
            }

            fullyConformant = (nonConformantFiles == 0) && errors.isEmpty();
        }

        // Getters and setters
        public String getReportId() { return reportId; }
        public void setReportId(String reportId) { this.reportId = reportId; }

        public LocalDateTime getGeneratedAt() { return generatedAt; }
        public void setGeneratedAt(LocalDateTime generatedAt) { this.generatedAt = generatedAt; }

        public String getOriginalDirectory() { return originalDirectory; }
        public void setOriginalDirectory(String originalDirectory) { this.originalDirectory = originalDirectory; }

        public String getAnonymizedDirectory() { return anonymizedDirectory; }
        public void setAnonymizedDirectory(String anonymizedDirectory) { this.anonymizedDirectory = anonymizedDirectory; }

        public String getScriptName() { return scriptName; }
        public void setScriptName(String scriptName) { this.scriptName = scriptName; }

        public String getScriptContent() { return scriptContent; }
        public void setScriptContent(String scriptContent) { this.scriptContent = scriptContent; }

        public int getTotalFilesOriginal() { return totalFilesOriginal; }
        public void setTotalFilesOriginal(int totalFilesOriginal) { this.totalFilesOriginal = totalFilesOriginal; }

        public int getTotalFilesAnonymized() { return totalFilesAnonymized; }
        public void setTotalFilesAnonymized(int totalFilesAnonymized) { this.totalFilesAnonymized = totalFilesAnonymized; }

        public int getMatchedFiles() { return matchedFiles; }
        public void setMatchedFiles(int matchedFiles) { this.matchedFiles = matchedFiles; }

        public int getNonConformantFiles() { return nonConformantFiles; }
        public void setNonConformantFiles(int nonConformantFiles) { this.nonConformantFiles = nonConformantFiles; }

        public Map<String, TagSummary> getTagSummary() { return tagSummary; }
        public void setTagSummary(Map<String, TagSummary> tagSummary) { this.tagSummary = tagSummary; }

        public List<FileComparison> getFileComparisons() { return fileComparisons; }
        public void setFileComparisons(List<FileComparison> fileComparisons) { this.fileComparisons = fileComparisons; }

        public List<String> getErrors() { return errors; }
        public void setErrors(List<String> errors) { this.errors = errors; }

        public boolean isFullyConformant() { return fullyConformant; }
        public void setFullyConformant(boolean fullyConformant) { this.fullyConformant = fullyConformant; }

        public int getTotalChanges() { return totalChanges; }
        public void setTotalChanges(int totalChanges) { this.totalChanges = totalChanges; }

        public int getPhiFieldsModified() { return phiFieldsModified; }
        public void setPhiFieldsModified(int phiFieldsModified) { this.phiFieldsModified = phiFieldsModified; }
    }

    public static class TagSummary {
        private String tagHex;
        private String tagName;
        private int changeCount;

        // Default constructor for JSON deserialization
        public TagSummary() {
        }

        public TagSummary(String tagHex, String tagName) {
            this.tagHex = tagHex;
            this.tagName = tagName;
            this.changeCount = 0;
        }

        public void incrementCount() {
            changeCount++;
        }

        public String getTagHex() { return tagHex; }
        public void setTagHex(String tagHex) { this.tagHex = tagHex; }

        public String getTagName() { return tagName; }
        public void setTagName(String tagName) { this.tagName = tagName; }

        public int getChangeCount() { return changeCount; }
        public void setChangeCount(int changeCount) { this.changeCount = changeCount; }
    }

    public static class FileComparison {
        private String originalFile;
        private String anonymizedFile;
        private List<TagChange> changes = new ArrayList<>();
        private List<String> conformanceIssues = new ArrayList<>();
        private List<String> residualPhiWarnings = new ArrayList<>();

        public void addConformanceIssue(String issue) {
            conformanceIssues.add(issue);
        }

        public void addResidualPhiWarning(String warning) {
            residualPhiWarnings.add(warning);
        }

        public String getOriginalFile() { return originalFile; }
        public void setOriginalFile(String originalFile) { this.originalFile = originalFile; }

        public String getAnonymizedFile() { return anonymizedFile; }
        public void setAnonymizedFile(String anonymizedFile) { this.anonymizedFile = anonymizedFile; }

        public List<TagChange> getChanges() { return changes; }
        public void setChanges(List<TagChange> changes) { this.changes = changes; }

        public List<String> getConformanceIssues() { return conformanceIssues; }
        public void setConformanceIssues(List<String> conformanceIssues) { this.conformanceIssues = conformanceIssues; }

        public List<String> getResidualPhiWarnings() { return residualPhiWarnings; }
        public void setResidualPhiWarnings(List<String> residualPhiWarnings) { this.residualPhiWarnings = residualPhiWarnings; }
    }

    public static class TagChange {
        private String tagHex;
        private String tagName;
        private String originalValue;
        private String anonymizedValue;
        private String action; // removed, replaced, hashed, added
        private boolean phi;

        public String getTagHex() { return tagHex; }
        public void setTagHex(String tagHex) { this.tagHex = tagHex; }

        public String getTagName() { return tagName; }
        public void setTagName(String tagName) { this.tagName = tagName; }

        public String getOriginalValue() { return originalValue; }
        public void setOriginalValue(String originalValue) { this.originalValue = originalValue; }

        public String getAnonymizedValue() { return anonymizedValue; }
        public void setAnonymizedValue(String anonymizedValue) { this.anonymizedValue = anonymizedValue; }

        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }

        public boolean isPhi() { return phi; }
        public void setPhi(boolean phi) { this.phi = phi; }
    }
}
