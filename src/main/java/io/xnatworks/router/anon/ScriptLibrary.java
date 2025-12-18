/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.anon;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages a library of DicomEdit anonymization scripts.
 * Scripts can be:
 * - Stored locally in the scripts directory
 * - Fetched from XNAT endpoints (site-wide or project-specific)
 * - Created/edited via the admin UI
 *
 * Scripts are referenced by name in route configurations.
 *
 * Directory structure:
 * scripts/
 * ├── library.json           # Script metadata index
 * ├── hipaa_standard.das     # Standard HIPAA de-identification
 * ├── minimal.das            # Minimal anonymization
 * ├── research_safe.das      # Research-safe anonymization
 * ├── custom/                # User-created scripts
 * │   └── my_script.das
 * └── imported/              # Scripts imported from XNAT
 *     └── xnat_site.das
 */
public class ScriptLibrary {
    private static final Logger log = LoggerFactory.getLogger(ScriptLibrary.class);

    private final Path scriptsDir;
    private final Path libraryFile;
    private final ObjectMapper objectMapper;

    private Map<String, ScriptEntry> scripts = new LinkedHashMap<>();

    public ScriptLibrary(Path scriptsDir) throws IOException {
        this.scriptsDir = scriptsDir;
        this.libraryFile = scriptsDir.resolve("library.json");
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // Create directories
        Files.createDirectories(scriptsDir);
        Files.createDirectories(scriptsDir.resolve("custom"));
        Files.createDirectories(scriptsDir.resolve("imported"));

        // Load existing library
        loadLibrary();

        // Initialize with built-in scripts if empty
        if (scripts.isEmpty()) {
            initializeBuiltInScripts();
        }
    }

    /**
     * Load library index from disk.
     */
    private void loadLibrary() {
        if (!Files.exists(libraryFile)) {
            return;
        }

        try {
            ScriptLibraryData data = objectMapper.readValue(libraryFile.toFile(), ScriptLibraryData.class);
            if (data.getScripts() != null) {
                for (ScriptEntry entry : data.getScripts()) {
                    scripts.put(entry.getName(), entry);
                }
            }
            log.info("Loaded {} scripts from library", scripts.size());
        } catch (IOException e) {
            log.error("Failed to load script library: {}", e.getMessage(), e);
        }
    }

    /**
     * Save library index to disk.
     */
    private void saveLibrary() {
        try {
            ScriptLibraryData data = new ScriptLibraryData();
            data.setLastModified(LocalDateTime.now());
            data.setScripts(new ArrayList<>(scripts.values()));
            objectMapper.writeValue(libraryFile.toFile(), data);
        } catch (IOException e) {
            log.error("Failed to save script library: {}", e.getMessage(), e);
        }
    }

    /**
     * Initialize with built-in scripts.
     */
    private void initializeBuiltInScripts() throws IOException {
        log.info("Initializing built-in anonymization scripts");

        // HIPAA Safe Harbor de-identification
        addBuiltInScript("hipaa_standard", "HIPAA Safe Harbor",
                "Standard HIPAA Safe Harbor de-identification profile",
                ScriptCategory.STANDARD, getHipaaStandardScript());

        // HIPAA with AccessionNumber preserved (for honest broker)
        addBuiltInScript("hipaa_broker", "HIPAA + Honest Broker",
                "HIPAA Safe Harbor with AccessionNumber preserved for honest broker",
                ScriptCategory.STANDARD, getHipaaBrokerScript());

        // Minimal anonymization
        addBuiltInScript("minimal", "Minimal Anonymization",
                "Removes only patient name and ID - preserves most metadata",
                ScriptCategory.STANDARD, getMinimalScript());

        // Research-safe anonymization
        addBuiltInScript("research_safe", "Research Safe",
                "Preserves clinically relevant data while removing identifiers",
                ScriptCategory.STANDARD, getResearchSafeScript());

        // Passthrough (no changes)
        addBuiltInScript("passthrough", "No Anonymization",
                "Passes DICOM through without any modifications",
                ScriptCategory.STANDARD, getPassthroughScript());

        saveLibrary();
    }

    private void addBuiltInScript(String name, String displayName, String description,
                                   ScriptCategory category, String content) throws IOException {
        Path scriptFile = scriptsDir.resolve(name + ".das");
        Files.writeString(scriptFile, content);

        ScriptEntry entry = new ScriptEntry();
        entry.setName(name);
        entry.setDisplayName(displayName);
        entry.setDescription(description);
        entry.setCategory(category);
        entry.setFilePath(scriptFile.toString());
        entry.setBuiltIn(true);
        entry.setCreatedAt(LocalDateTime.now());
        entry.setModifiedAt(LocalDateTime.now());

        scripts.put(name, entry);
    }

    /**
     * Get script by name.
     */
    public ScriptEntry getScript(String name) {
        return scripts.get(name);
    }

    /**
     * Get script content by name.
     */
    public String getScriptContent(String name) throws IOException {
        ScriptEntry entry = scripts.get(name);
        if (entry == null) {
            throw new IllegalArgumentException("Script not found: " + name);
        }
        return Files.readString(Paths.get(entry.getFilePath()));
    }

    /**
     * Get all scripts.
     */
    public List<ScriptEntry> getAllScripts() {
        return new ArrayList<>(scripts.values());
    }

    /**
     * Get scripts by category.
     */
    public List<ScriptEntry> getScriptsByCategory(ScriptCategory category) {
        return scripts.values().stream()
                .filter(s -> s.getCategory() == category)
                .collect(Collectors.toList());
    }

    /**
     * Add a new script.
     */
    public ScriptEntry addScript(String name, String displayName, String description,
                                  ScriptCategory category, String content) throws IOException {
        if (scripts.containsKey(name)) {
            throw new IllegalArgumentException("Script already exists: " + name);
        }

        Path scriptFile = scriptsDir.resolve("custom").resolve(name + ".das");
        Files.writeString(scriptFile, content);

        ScriptEntry entry = new ScriptEntry();
        entry.setName(name);
        entry.setDisplayName(displayName);
        entry.setDescription(description);
        entry.setCategory(category);
        entry.setFilePath(scriptFile.toString());
        entry.setBuiltIn(false);
        entry.setCreatedAt(LocalDateTime.now());
        entry.setModifiedAt(LocalDateTime.now());

        scripts.put(name, entry);
        saveLibrary();

        log.info("Added script: {}", name);
        return entry;
    }

    /**
     * Update an existing script.
     */
    public ScriptEntry updateScript(String name, String displayName, String description,
                                     String content) throws IOException {
        ScriptEntry entry = scripts.get(name);
        if (entry == null) {
            throw new IllegalArgumentException("Script not found: " + name);
        }

        if (entry.isBuiltIn()) {
            throw new IllegalArgumentException("Cannot modify built-in script: " + name);
        }

        if (displayName != null) entry.setDisplayName(displayName);
        if (description != null) entry.setDescription(description);
        if (content != null) {
            Files.writeString(Paths.get(entry.getFilePath()), content);
        }
        entry.setModifiedAt(LocalDateTime.now());

        saveLibrary();

        log.info("Updated script: {}", name);
        return entry;
    }

    /**
     * Delete a script.
     */
    public void deleteScript(String name) throws IOException {
        ScriptEntry entry = scripts.get(name);
        if (entry == null) {
            throw new IllegalArgumentException("Script not found: " + name);
        }

        if (entry.isBuiltIn()) {
            throw new IllegalArgumentException("Cannot delete built-in script: " + name);
        }

        Files.deleteIfExists(Paths.get(entry.getFilePath()));
        scripts.remove(name);
        saveLibrary();

        log.info("Deleted script: {}", name);
    }

    /**
     * Import script from XNAT endpoint.
     */
    public ScriptEntry importFromXnat(String name, String xnatUrl, String projectId,
                                       String content) throws IOException {
        String safeName = name.replaceAll("[^a-zA-Z0-9_-]", "_");
        Path scriptFile = scriptsDir.resolve("imported").resolve(safeName + ".das");
        Files.writeString(scriptFile, content);

        ScriptEntry entry = new ScriptEntry();
        entry.setName(safeName);
        entry.setDisplayName(name);
        entry.setDescription("Imported from " + xnatUrl + (projectId != null ? " (project: " + projectId + ")" : " (site-wide)"));
        entry.setCategory(ScriptCategory.IMPORTED);
        entry.setFilePath(scriptFile.toString());
        entry.setBuiltIn(false);
        entry.setSourceUrl(xnatUrl);
        entry.setSourceProject(projectId);
        entry.setCreatedAt(LocalDateTime.now());
        entry.setModifiedAt(LocalDateTime.now());

        scripts.put(safeName, entry);
        saveLibrary();

        log.info("Imported script from XNAT: {} -> {}", xnatUrl, safeName);
        return entry;
    }

    /**
     * Duplicate a script.
     */
    public ScriptEntry duplicateScript(String sourceName, String newName) throws IOException {
        ScriptEntry source = scripts.get(sourceName);
        if (source == null) {
            throw new IllegalArgumentException("Script not found: " + sourceName);
        }

        String content = Files.readString(Paths.get(source.getFilePath()));
        return addScript(newName, source.getDisplayName() + " (Copy)",
                source.getDescription(), ScriptCategory.CUSTOM, content);
    }

    /**
     * List all scripts.
     */
    public List<ScriptEntry> listScripts() {
        return new ArrayList<>(scripts.values());
    }

    /**
     * Add a custom script with simpler signature for API.
     */
    public ScriptEntry addCustomScript(String name, String description, String content) throws IOException {
        return addScript(name, name, description, ScriptCategory.CUSTOM, content);
    }

    /**
     * Update script with simpler signature for API.
     */
    public ScriptEntry updateScript(String name, String description, String content) throws IOException {
        return updateScript(name, name, description, content);
    }

    /**
     * Validate script syntax.
     */
    public ValidationResult validateScript(String content) {
        ValidationResult result = new ValidationResult();
        result.setValid(true);

        if (content == null || content.trim().isEmpty()) {
            result.setValid(false);
            result.getErrors().add("Script content is empty");
            return result;
        }

        int tagCount = 0;
        String[] lines = content.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            int lineNum = i + 1;

            // Skip comments and empty lines
            if (line.isEmpty() || line.startsWith("//")) {
                continue;
            }

            // Check for version statement
            if (line.startsWith("version")) {
                if (!line.matches("version\\s+\"[\\d.]+\"")) {
                    result.getWarnings().add("Line " + lineNum + ": Invalid version format");
                }
                continue;
            }

            // Check for tag operations
            if (line.startsWith("(")) {
                tagCount++;

                // Basic tag format validation
                if (!line.matches("\\([0-9A-Fa-f]{4},[0-9A-Fa-f]{4}\\).*")) {
                    result.getErrors().add("Line " + lineNum + ": Invalid DICOM tag format");
                    result.setValid(false);
                }
            }
        }

        result.setTagCount(tagCount);

        if (tagCount == 0 && !content.contains("keep")) {
            result.getWarnings().add("Script doesn't modify any tags - may be a passthrough");
        }

        return result;
    }

    /**
     * Get source information for a script entry.
     */
    public String getSource(ScriptEntry entry) {
        if (entry.isBuiltIn()) {
            return "built-in";
        } else if (entry.getSourceUrl() != null) {
            return "xnat:" + entry.getSourceUrl();
        } else {
            return "custom";
        }
    }

    /**
     * Validation result for scripts.
     */
    public static class ValidationResult {
        private boolean valid;
        private List<String> errors = new ArrayList<>();
        private List<String> warnings = new ArrayList<>();
        private int tagCount;

        public boolean isValid() { return valid; }
        public void setValid(boolean valid) { this.valid = valid; }

        public List<String> getErrors() { return errors; }
        public void setErrors(List<String> errors) { this.errors = errors; }

        public List<String> getWarnings() { return warnings; }
        public void setWarnings(List<String> warnings) { this.warnings = warnings; }

        public int getTagCount() { return tagCount; }
        public void setTagCount(int tagCount) { this.tagCount = tagCount; }
    }

    // Built-in script templates

    private String getHipaaStandardScript() {
        // Note: version statement removed due to DicomEdit 6.0.2 parser bug
        // The parser incorrectly tokenizes 'version "6.3"' as a single token
        return "// HIPAA Safe Harbor De-identification Script\n" +
                "// Removes 18 HIPAA identifiers as defined in 45 CFR 164.514(b)(2)\n" +
                "\n" +
                "// Patient identifiers\n" +
                "(0010,0010) := \"ANONYMOUS\"           // Patient Name\n" +
                "(0010,0020) := hashUID[(0010,0020)]  // Patient ID\n" +
                "(0010,0030) := \"\"                    // Patient Birth Date\n" +
                "(0010,0032) := \"\"                    // Patient Birth Time\n" +
                "(0010,0050) := \"\"                    // Patient Insurance Plan Code\n" +
                "(0010,1000) := \"\"                    // Other Patient IDs\n" +
                "(0010,1001) := \"\"                    // Other Patient Names\n" +
                "(0010,1005) := \"\"                    // Patient Birth Name\n" +
                "(0010,1010) := \"\"                    // Patient Age\n" +
                "(0010,1020) := \"\"                    // Patient Size\n" +
                "(0010,1030) := \"\"                    // Patient Weight\n" +
                "(0010,1040) := \"\"                    // Patient Address\n" +
                "(0010,1060) := \"\"                    // Patient Mother Birth Name\n" +
                "(0010,2154) := \"\"                    // Patient Telephone Numbers\n" +
                "(0010,2160) := \"\"                    // Ethnic Group\n" +
                "(0010,21B0) := \"\"                    // Additional Patient History\n" +
                "(0010,21F0) := \"\"                    // Patient Religious Preference\n" +
                "(0010,4000) := \"\"                    // Patient Comments\n" +
                "\n" +
                "// Study identifiers\n" +
                "(0008,0020) := \"\"                    // Study Date\n" +
                "(0008,0021) := \"\"                    // Series Date\n" +
                "(0008,0022) := \"\"                    // Acquisition Date\n" +
                "(0008,0023) := \"\"                    // Content Date\n" +
                "(0008,0030) := \"\"                    // Study Time\n" +
                "(0008,0031) := \"\"                    // Series Time\n" +
                "(0008,0032) := \"\"                    // Acquisition Time\n" +
                "(0008,0033) := \"\"                    // Content Time\n" +
                "(0008,0050) := \"\"                    // Accession Number\n" +
                "(0008,0080) := \"\"                    // Institution Name\n" +
                "(0008,0081) := \"\"                    // Institution Address\n" +
                "(0008,0090) := \"\"                    // Referring Physician Name\n" +
                "(0008,0092) := \"\"                    // Referring Physician Address\n" +
                "(0008,0094) := \"\"                    // Referring Physician Tel Numbers\n" +
                "(0008,1010) := \"\"                    // Station Name\n" +
                "(0008,1040) := \"\"                    // Institutional Department Name\n" +
                "(0008,1048) := \"\"                    // Physician(s) of Record\n" +
                "(0008,1050) := \"\"                    // Performing Physician Name\n" +
                "(0008,1060) := \"\"                    // Name of Physician Reading Study\n" +
                "(0008,1070) := \"\"                    // Operators Name\n" +
                "\n" +
                "// UIDs - replace with new values\n" +
                "(0020,000D) := hashUID[(0020,000D)] // Study Instance UID\n" +
                "(0020,000E) := hashUID[(0020,000E)] // Series Instance UID\n" +
                "(0008,0018) := hashUID[(0008,0018)] // SOP Instance UID\n" +
                "// Note: SOP Class UID (0008,0016) is implicitly kept (not modified)\n" +
                "\n" +
                "// De-identification marker\n" +
                "(0012,0062) := \"YES\"                 // Patient Identity Removed\n" +
                "(0012,0063) := \"HIPAA Safe Harbor\"  // De-identification Method\n";
    }

    private String getHipaaBrokerScript() {
        // HIPAA Safe Harbor with AccessionNumber preserved for honest broker use
        return "// HIPAA Safe Harbor + Honest Broker Script\n" +
                "// Based on HIPAA Safe Harbor but preserves AccessionNumber for honest broker\n" +
                "// Use this when honest broker is enabled and needs AccessionNumber for mapping\n" +
                "\n" +
                "// Patient identifiers\n" +
                "(0010,0010) := \"ANONYMOUS\"           // Patient Name\n" +
                "(0010,0020) := hashUID[(0010,0020)]  // Patient ID\n" +
                "(0010,0030) := \"\"                    // Patient Birth Date\n" +
                "(0010,0032) := \"\"                    // Patient Birth Time\n" +
                "(0010,0050) := \"\"                    // Patient Insurance Plan Code\n" +
                "(0010,1000) := \"\"                    // Other Patient IDs\n" +
                "(0010,1001) := \"\"                    // Other Patient Names\n" +
                "(0010,1005) := \"\"                    // Patient Birth Name\n" +
                "(0010,1010) := \"\"                    // Patient Age\n" +
                "(0010,1020) := \"\"                    // Patient Size\n" +
                "(0010,1030) := \"\"                    // Patient Weight\n" +
                "(0010,1040) := \"\"                    // Patient Address\n" +
                "(0010,1060) := \"\"                    // Patient Mother Birth Name\n" +
                "(0010,2154) := \"\"                    // Patient Telephone Numbers\n" +
                "(0010,2160) := \"\"                    // Ethnic Group\n" +
                "(0010,21B0) := \"\"                    // Additional Patient History\n" +
                "(0010,21F0) := \"\"                    // Patient Religious Preference\n" +
                "(0010,4000) := \"\"                    // Patient Comments\n" +
                "\n" +
                "// Study identifiers (AccessionNumber preserved for honest broker)\n" +
                "(0008,0020) := \"\"                    // Study Date\n" +
                "(0008,0021) := \"\"                    // Series Date\n" +
                "(0008,0022) := \"\"                    // Acquisition Date\n" +
                "(0008,0023) := \"\"                    // Content Date\n" +
                "(0008,0030) := \"\"                    // Study Time\n" +
                "(0008,0031) := \"\"                    // Series Time\n" +
                "(0008,0032) := \"\"                    // Acquisition Time\n" +
                "(0008,0033) := \"\"                    // Content Time\n" +
                "// (0008,0050) AccessionNumber - PRESERVED for honest broker mapping\n" +
                "(0008,0080) := \"\"                    // Institution Name\n" +
                "(0008,0081) := \"\"                    // Institution Address\n" +
                "(0008,0090) := \"\"                    // Referring Physician Name\n" +
                "(0008,0092) := \"\"                    // Referring Physician Address\n" +
                "(0008,0094) := \"\"                    // Referring Physician Tel Numbers\n" +
                "(0008,1010) := \"\"                    // Station Name\n" +
                "(0008,1040) := \"\"                    // Institutional Department Name\n" +
                "(0008,1048) := \"\"                    // Physician(s) of Record\n" +
                "(0008,1050) := \"\"                    // Performing Physician Name\n" +
                "(0008,1060) := \"\"                    // Name of Physician Reading Study\n" +
                "(0008,1070) := \"\"                    // Operators Name\n" +
                "\n" +
                "// UIDs - replace with new values\n" +
                "(0020,000D) := hashUID[(0020,000D)] // Study Instance UID\n" +
                "(0020,000E) := hashUID[(0020,000E)] // Series Instance UID\n" +
                "(0008,0018) := hashUID[(0008,0018)] // SOP Instance UID\n" +
                "// Note: SOP Class UID (0008,0016) is implicitly kept (not modified)\n" +
                "\n" +
                "// De-identification marker\n" +
                "(0012,0062) := \"YES\"                 // Patient Identity Removed\n" +
                "(0012,0063) := \"HIPAA + Honest Broker\"  // De-identification Method\n";
    }

    private String getMinimalScript() {
        return "// Minimal Anonymization Script\n" +
                "// Removes only the most basic patient identifiers\n" +
                "// Preserves dates and most metadata\n" +
                "\n" +
                "// Patient identifiers only\n" +
                "(0010,0010) := \"ANONYMOUS\"           // Patient Name\n" +
                "(0010,0020) := hashUID[(0010,0020)]  // Patient ID\n" +
                "\n" +
                "// De-identification marker\n" +
                "(0012,0062) := \"YES\"                 // Patient Identity Removed\n" +
                "(0012,0063) := \"Minimal\"            // De-identification Method\n";
    }

    private String getResearchSafeScript() {
        // Note: 'keep' statements removed due to DicomEdit 6.0.2 parser bug
        // Tags not mentioned in the script are implicitly preserved
        return "// Research-Safe Anonymization Script\n" +
                "// Removes identifiers while preserving clinically relevant data\n" +
                "// Note: Age, Sex, Weight, Height, and dates are implicitly preserved\n" +
                "\n" +
                "// Patient identifiers to anonymize\n" +
                "(0010,0010) := \"RESEARCH_SUBJECT\"    // Patient Name\n" +
                "(0010,0020) := hashUID[(0010,0020)]  // Patient ID\n" +
                "(0010,1000) := \"\"                    // Other Patient IDs\n" +
                "(0010,1001) := \"\"                    // Other Patient Names\n" +
                "(0010,1040) := \"\"                    // Patient Address\n" +
                "(0010,2154) := \"\"                    // Patient Telephone Numbers\n" +
                "\n" +
                "// Institution info\n" +
                "(0008,0080) := \"RESEARCH_SITE\"       // Institution Name\n" +
                "(0008,0081) := \"\"                    // Institution Address\n" +
                "(0008,0090) := \"\"                    // Referring Physician Name\n" +
                "(0008,1050) := \"\"                    // Performing Physician Name\n" +
                "(0008,1070) := \"\"                    // Operators Name\n" +
                "\n" +
                "// UIDs - replace with new values\n" +
                "(0020,000D) := hashUID[(0020,000D)] // Study Instance UID\n" +
                "(0020,000E) := hashUID[(0020,000E)] // Series Instance UID\n" +
                "(0008,0018) := hashUID[(0008,0018)] // SOP Instance UID\n" +
                "\n" +
                "// De-identification marker\n" +
                "(0012,0062) := \"YES\"                 // Patient Identity Removed\n" +
                "(0012,0063) := \"Research Safe\"      // De-identification Method\n";
    }

    private String getPassthroughScript() {
        return "// Passthrough Script\n" +
                "// No modifications - passes DICOM through unchanged\n" +
                "// Use this for routing without anonymization\n" +
                "\n" +
                "// Keep all attributes\n";
    }

    // Data classes

    public enum ScriptCategory {
        STANDARD,   // Built-in standard scripts
        CUSTOM,     // User-created scripts
        IMPORTED    // Imported from XNAT
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ScriptEntry {
        private String name;
        private String displayName;
        private String description;
        private ScriptCategory category;
        private String filePath;
        private boolean builtIn;
        private String sourceUrl;      // For imported scripts
        private String sourceProject;  // For imported scripts
        private LocalDateTime createdAt;
        private LocalDateTime modifiedAt;

        // Getters and setters
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public ScriptCategory getCategory() { return category; }
        public void setCategory(ScriptCategory category) { this.category = category; }

        public String getFilePath() { return filePath; }
        public void setFilePath(String filePath) { this.filePath = filePath; }

        public boolean isBuiltIn() { return builtIn; }
        public void setBuiltIn(boolean builtIn) { this.builtIn = builtIn; }

        public String getSourceUrl() { return sourceUrl; }
        public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }

        public String getSourceProject() { return sourceProject; }
        public void setSourceProject(String sourceProject) { this.sourceProject = sourceProject; }

        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

        public LocalDateTime getModifiedAt() { return modifiedAt; }
        public void setModifiedAt(LocalDateTime modifiedAt) { this.modifiedAt = modifiedAt; }

        public String getSource() {
            if (builtIn) {
                return "built-in";
            } else if (sourceUrl != null) {
                return "xnat:" + sourceUrl;
            } else {
                return "custom";
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ScriptLibraryData {
        private LocalDateTime lastModified;
        private List<ScriptEntry> scripts;

        public LocalDateTime getLastModified() { return lastModified; }
        public void setLastModified(LocalDateTime lastModified) { this.lastModified = lastModified; }

        public List<ScriptEntry> getScripts() { return scripts; }
        public void setScripts(List<ScriptEntry> scripts) { this.scripts = scripts; }
    }
}
