/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.anon;

import io.xnatworks.router.xnat.XnatClient;
import org.dcm4che2.data.BasicDicomObject;
import org.dcm4che2.data.DicomObject;
import org.dcm4che2.io.DicomInputStream;
import org.dcm4che2.io.DicomOutputStream;
import org.nrg.dcm.edit.ScriptApplicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Anonymization service using DicomEdit library directly.
 * Integrates with ScriptLibrary for script management.
 */
public class AnonymizationService {
    private static final Logger log = LoggerFactory.getLogger(AnonymizationService.class);

    private final ScriptLibrary scriptLibrary;
    private final XnatClient xnatClient;  // Optional - for fetching scripts from XNAT

    /**
     * Create service with script library (standalone mode).
     */
    public AnonymizationService(ScriptLibrary scriptLibrary) {
        this.scriptLibrary = scriptLibrary;
        this.xnatClient = null;
        log.info("AnonymizationService initialized with direct DicomEdit integration");
    }

    /**
     * Create service with script library and XNAT client (for fetching scripts).
     */
    public AnonymizationService(ScriptLibrary scriptLibrary, XnatClient xnatClient) {
        this.scriptLibrary = scriptLibrary;
        this.xnatClient = xnatClient;
        log.info("AnonymizationService initialized with direct DicomEdit integration and XNAT client");
    }

    /**
     * Check if anonymization is available.
     */
    public boolean isAvailable() {
        return true; // DicomEdit is on classpath
    }

    /**
     * Create a ScriptApplicator from script content.
     */
    private ScriptApplicator createApplicator(String script, Map<String, String> variables) throws IOException {
        ByteArrayInputStream scriptStream = new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8));
        ScriptApplicator applicator = new ScriptApplicator(scriptStream);

        // Set variables
        if (variables != null) {
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                applicator.setVariable(entry.getKey(), entry.getValue());
            }
        }

        return applicator;
    }

    /**
     * Anonymize a single DICOM file using a script from the library.
     *
     * @param inputFile    Input DICOM file
     * @param outputFile   Output DICOM file
     * @param scriptName   Name of script in the library
     * @return true if successful
     */
    public boolean anonymizeFile(Path inputFile, Path outputFile, String scriptName) throws IOException {
        String scriptContent;
        try {
            scriptContent = scriptLibrary.getScriptContent(scriptName);
        } catch (Exception e) {
            throw new IOException("Script not found: " + scriptName, e);
        }
        return anonymizeFileWithScript(inputFile, outputFile, scriptContent, Collections.emptyMap());
    }

    /**
     * Anonymize a single DICOM file with a script and variables.
     *
     * @param inputFile    Input DICOM file
     * @param outputFile   Output DICOM file
     * @param script       DicomEdit script content
     * @param variables    Script variables (e.g., project, subject, session)
     * @return true if successful
     */
    public boolean anonymizeFileWithScript(Path inputFile, Path outputFile, String script,
                                            Map<String, String> variables) throws IOException {
        ScriptApplicator applicator = createApplicator(script, variables);

        // Read input DICOM using dcm4che2
        DicomObject dcmObj;
        try (DicomInputStream dis = new DicomInputStream(inputFile.toFile())) {
            dcmObj = dis.readDicomObject();
        }

        // Apply the script
        applicator.apply(inputFile.toFile(), dcmObj);

        // Ensure output directory exists
        Files.createDirectories(outputFile.getParent());

        // Write output DICOM
        try (DicomOutputStream dos = new DicomOutputStream(outputFile.toFile())) {
            dos.writeDicomFile(dcmObj);
        }

        return true;
    }

    /**
     * Anonymize files using a script from the library.
     *
     * @param inputDir     Directory containing DICOM files
     * @param outputDir    Directory to write anonymized files
     * @param scriptName   Name of script in the library
     * @return Anonymization result
     */
    public AnonymizationResult anonymize(Path inputDir, Path outputDir, String scriptName)
            throws IOException {
        String scriptContent;
        try {
            scriptContent = scriptLibrary.getScriptContent(scriptName);
        } catch (Exception e) {
            throw new IOException("Script not found: " + scriptName, e);
        }
        return anonymizeWithScript(inputDir, outputDir, scriptContent);
    }

    /**
     * Anonymize files using a custom script string.
     */
    public AnonymizationResult anonymizeWithScript(Path inputDir, Path outputDir, String script)
            throws IOException {
        return anonymizeWithScript(inputDir, outputDir, script, Collections.emptyMap());
    }

    /**
     * Anonymize files using a custom script string with variables.
     */
    public AnonymizationResult anonymizeWithScript(Path inputDir, Path outputDir, String script,
                                                    Map<String, String> variables) throws IOException {
        log.info("Starting anonymization: input={}, output={}", inputDir, outputDir);

        long startTime = System.currentTimeMillis();

        // Create output directory
        Files.createDirectories(outputDir);

        AnonymizationResult result = new AnonymizationResult();
        result.setInputDirectory(inputDir.toString());
        result.setOutputDirectory(outputDir.toString());

        AtomicInteger inputCount = new AtomicInteger(0);
        AtomicInteger outputCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        // Create the applicator once for all files
        ScriptApplicator applicator = createApplicator(script, variables);

        // Process all DICOM files
        try {
            Files.walk(inputDir)
                    .filter(Files::isRegularFile)
                    .filter(this::isDicomFile)
                    .forEach(inputFile -> {
                        inputCount.incrementAndGet();

                        // Create output path preserving directory structure
                        Path relativePath = inputDir.relativize(inputFile);
                        Path outputFile = outputDir.resolve(relativePath);

                        try {
                            // Read input DICOM using dcm4che2
                            DicomObject dcmObj;
                            try (DicomInputStream dis = new DicomInputStream(inputFile.toFile())) {
                                dcmObj = dis.readDicomObject();
                            }

                            // Apply the script
                            applicator.apply(inputFile.toFile(), dcmObj);

                            // Ensure output directory exists
                            Files.createDirectories(outputFile.getParent());

                            // Write output DICOM
                            try (DicomOutputStream dos = new DicomOutputStream(outputFile.toFile())) {
                                dos.writeDicomFile(dcmObj);
                            }

                            outputCount.incrementAndGet();

                        } catch (Exception e) {
                            errorCount.incrementAndGet();
                            log.error("Error anonymizing {}: {}", inputFile.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            throw new IOException("Error processing directory: " + e.getMessage(), e);
        }

        result.setInputFiles(inputCount.get());
        result.setOutputFiles(outputCount.get());
        result.setErrorCount(errorCount.get());
        result.setDurationMs(System.currentTimeMillis() - startTime);
        result.setSuccess(errorCount.get() == 0);

        log.info("Anonymization complete: {} -> {} files ({} errors) in {}ms",
                inputCount.get(), outputCount.get(), errorCount.get(), result.getDurationMs());

        return result;
    }

    /**
     * Check if a file appears to be a DICOM file.
     */
    private boolean isDicomFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        // Include .dcm files and files without extension (common for DICOM)
        if (name.endsWith(".dcm")) return true;
        if (!name.contains(".")) return true;
        // Also check for common DICOM part file patterns
        if (name.matches("\\d+")) return true;
        return false;
    }

    /**
     * Anonymize using merged site + project scripts from XNAT.
     */
    public AnonymizationResult anonymizeWithXnatScripts(Path inputDir, Path outputDir,
                                                         String projectId) throws IOException {
        if (xnatClient == null) {
            throw new IOException("XNAT client not configured");
        }

        // Fetch and merge scripts
        String script = getMergedXnatScript(projectId);

        // Create variables map with XNAT context
        Map<String, String> variables = new HashMap<>();
        variables.put("project", projectId);

        return anonymizeWithScript(inputDir, outputDir, script, variables);
    }

    /**
     * Get merged anonymization script from XNAT (site + project level).
     */
    public String getMergedXnatScript(String projectId) throws IOException {
        if (xnatClient == null) {
            throw new IOException("XNAT client not configured");
        }

        // Get site script
        String siteScript = "";
        try {
            siteScript = xnatClient.fetchSiteAnonScript();
            if (siteScript == null) siteScript = "";
        } catch (Exception e) {
            log.debug("No site-level anon script available");
        }

        // Get project script
        String projectScript = "";
        if (projectId != null && !projectId.isEmpty()) {
            try {
                projectScript = xnatClient.fetchProjectAnonScript(projectId);
                if (projectScript == null) projectScript = "";
            } catch (Exception e) {
                log.debug("No project-level anon script for {}", projectId);
            }
        }

        // Merge scripts - project takes precedence
        if (!projectScript.isEmpty()) {
            return siteScript + "\n\n// Project-level overrides\n" + projectScript;
        }

        // Clean script for standalone use (remove XNAT variables)
        return cleanScriptForStandalone(siteScript);
    }

    /**
     * Import a script from XNAT into the local library.
     */
    public ScriptLibrary.ScriptEntry importScriptFromXnat(String name, String projectId)
            throws IOException {
        if (xnatClient == null) {
            throw new IOException("XNAT client not configured");
        }

        String script = getMergedXnatScript(projectId);
        return scriptLibrary.importFromXnat(name, xnatClient.getBaseUrl(), projectId, script);
    }

    /**
     * Remove XNAT-specific variables from script for standalone use.
     */
    private String cleanScriptForStandalone(String script) {
        if (script == null) return "";

        StringBuilder cleaned = new StringBuilder();
        for (String line : script.split("\n")) {
            String trimmed = line.trim();
            // Skip lines that use XNAT variables
            if (trimmed.matches("^(project|subject|session)\\s*!?=.*") ||
                    trimmed.contains(":= project") ||
                    trimmed.contains(":= subject") ||
                    trimmed.contains(":= session")) {
                continue;
            }
            cleaned.append(line).append("\n");
        }
        return cleaned.toString();
    }

    /**
     * Create ZIP file from anonymized output directory.
     */
    public Path createZip(Path outputDir, Path zipDir) throws IOException {
        Files.createDirectories(zipDir);

        String timestamp = java.time.format.DateTimeFormatter
                .ofPattern("yyyyMMdd_HHmmss")
                .format(java.time.LocalDateTime.now());

        Path zipFile = zipDir.resolve("anonymized_" + timestamp + ".zip");

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile.toFile()))) {
            Files.walk(outputDir)
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> !p.startsWith(zipDir))
                    .forEach(path -> {
                        try {
                            String entryName = outputDir.relativize(path).toString();
                            zos.putNextEntry(new ZipEntry(entryName));
                            Files.copy(path, zos);
                            zos.closeEntry();
                        } catch (IOException e) {
                            log.error("Error adding file to ZIP: {}", path, e);
                        }
                    });
        }

        long fileCount = Files.walk(outputDir)
                .filter(Files::isRegularFile)
                .filter(p -> !p.getFileName().toString().startsWith("."))
                .filter(p -> !p.startsWith(zipDir))
                .count();

        log.info("Created ZIP: {} ({} files, {} bytes)",
                zipFile.getFileName(), fileCount, Files.size(zipFile));

        return zipFile;
    }

    /**
     * Get the script library.
     */
    public ScriptLibrary getScriptLibrary() {
        return scriptLibrary;
    }

    /**
     * Anonymization result details.
     */
    public static class AnonymizationResult {
        private boolean success;
        private int inputFiles;
        private int outputFiles;
        private int errorCount;
        private long durationMs;
        private String inputDirectory;
        private String outputDirectory;

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }

        public int getInputFiles() { return inputFiles; }
        public void setInputFiles(int inputFiles) { this.inputFiles = inputFiles; }

        public int getOutputFiles() { return outputFiles; }
        public void setOutputFiles(int outputFiles) { this.outputFiles = outputFiles; }

        public int getErrorCount() { return errorCount; }
        public void setErrorCount(int errorCount) { this.errorCount = errorCount; }

        public long getDurationMs() { return durationMs; }
        public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

        public String getInputDirectory() { return inputDirectory; }
        public void setInputDirectory(String inputDirectory) { this.inputDirectory = inputDirectory; }

        public String getOutputDirectory() { return outputDirectory; }
        public void setOutputDirectory(String outputDirectory) { this.outputDirectory = outputDirectory; }

        public double getFilesPerSecond() {
            if (durationMs <= 0) return 0;
            return outputFiles / (durationMs / 1000.0);
        }
    }
}
