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
import org.nrg.dicom.dicomedit.DE6Script;
import org.nrg.dicom.dicomedit.ScriptApplicatorI;
import org.nrg.dicom.dicomedit.SerialScriptApplicator;
import org.nrg.dicom.mizer.exceptions.MizerException;
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
 *
 * For very large files (> 2GB), uses streaming-based anonymization via LargeFileAnonymizer
 * to avoid memory issues with dcm4che2's integer limitations.
 */
public class AnonymizationService {
    private static final Logger log = LoggerFactory.getLogger(AnonymizationService.class);

    private final ScriptLibrary scriptLibrary;
    private final XnatClient xnatClient;  // Optional - for fetching scripts from XNAT
    private final LargeFileAnonymizer largeFileAnonymizer = new LargeFileAnonymizer();
    private final AnonymizationVerifier verifier = new AnonymizationVerifier();

    // Configuration for verification
    private boolean verificationEnabled = true;
    private Integer expectedDateShiftDays = null;

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
     * Enable or disable anonymization verification.
     * When enabled, each anonymized file is verified to ensure the anonymization was applied correctly.
     * CRITICAL: Disabling verification is not recommended for production use.
     */
    public void setVerificationEnabled(boolean enabled) {
        this.verificationEnabled = enabled;
        if (!enabled) {
            log.warn("ANONYMIZATION VERIFICATION DISABLED - This is not recommended for production use!");
        }
    }

    /**
     * Set the expected date shift for verification.
     * This is used to verify that date shifting was applied correctly.
     *
     * @param days The expected date shift in days (positive = future, negative = past)
     */
    public void setExpectedDateShiftDays(Integer days) {
        this.expectedDateShiftDays = days;
        if (days != null) {
            log.info("Date shift verification enabled: expecting {} day shift", days);
        }
    }

    /**
     * Create verification config based on current settings.
     */
    private AnonymizationVerifier.VerificationConfig createVerificationConfig() {
        AnonymizationVerifier.VerificationConfig config = new AnonymizationVerifier.VerificationConfig();
        config.setExpectedDateShiftDays(expectedDateShiftDays);
        config.setVerifyUidsChanged(true);
        config.setVerifyPatientInfoModified(true);
        config.setVerifyDatesShifted(expectedDateShiftDays != null);
        config.setFailOnFirstError(false); // Collect all errors
        return config;
    }

    /**
     * Create a ScriptApplicatorI from script content using DicomEdit 6.6.0 API.
     */
    private ScriptApplicatorI createApplicator(String script, Map<String, String> variables) throws IOException {
        try {
            ByteArrayInputStream scriptStream = new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8));
            DE6Script de6Script = new DE6Script(scriptStream);
            ScriptApplicatorI applicator = new SerialScriptApplicator(Collections.singletonList(de6Script));

            // Set variables
            if (variables != null) {
                for (Map.Entry<String, String> entry : variables.entrySet()) {
                    applicator.setVariable(entry.getKey(), entry.getValue());
                }
            }

            return applicator;
        } catch (MizerException e) {
            throw new IOException("Failed to parse anonymization script: " + e.getMessage(), e);
        }
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
     * @throws IOException if anonymization fails
     * @throws AnonymizationVerifier.AnonymizationVerificationException if verification fails
     */
    public boolean anonymizeFileWithScript(Path inputFile, Path outputFile, String script,
                                            Map<String, String> variables) throws IOException {
        ScriptApplicatorI applicator = createApplicator(script, variables);

        // Read input DICOM using dcm4che2
        DicomObject originalDcm;
        try (DicomInputStream dis = new DicomInputStream(inputFile.toFile())) {
            originalDcm = dis.readDicomObject();
        }

        // Keep a copy for verification if enabled
        DicomObject dcmForVerification = null;
        if (verificationEnabled) {
            // Deep copy for verification - read again from file
            try (DicomInputStream dis = new DicomInputStream(inputFile.toFile())) {
                dcmForVerification = dis.readDicomObject();
            }
        }

        // Apply the script to the original object
        try {
            applicator.apply(inputFile.toFile(), originalDcm);
        } catch (MizerException e) {
            throw new IOException("Failed to apply anonymization script: " + e.getMessage(), e);
        }

        // Verify anonymization was applied correctly BEFORE writing
        if (verificationEnabled && dcmForVerification != null) {
            AnonymizationVerifier.VerificationConfig config = createVerificationConfig();
            AnonymizationVerifier.VerificationResult verifyResult = verifier.verify(dcmForVerification, originalDcm, config);

            if (!verifyResult.isAllPassed()) {
                String sopUid = originalDcm.getString(org.dcm4che2.data.Tag.SOPInstanceUID, "unknown");
                log.error("ANONYMIZATION VERIFICATION FAILED for {}:\n{}", inputFile.getFileName(), verifyResult);

                // Throw exception - do NOT write the file if verification fails
                throw new IOException("Anonymization verification failed for " + inputFile.getFileName() +
                        ": " + verifyResult.getFailedCount() + " checks failed. " +
                        "See logs for details. SOP UID: " + sopUid);
            }

            log.debug("Anonymization verified for {}: {} checks passed",
                    inputFile.getFileName(), verifyResult.getPassedCount());
        }

        // Ensure output directory exists
        Files.createDirectories(outputFile.getParent());

        // Write output DICOM (only if verification passed or disabled)
        try (DicomOutputStream dos = new DicomOutputStream(outputFile.toFile())) {
            dos.writeDicomFile(originalDcm);
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
        ScriptApplicatorI applicator = createApplicator(script, variables);

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
                            // Check if this is a large file requiring streaming approach
                            long fileBytes = Files.size(inputFile);
                            boolean isLargeFile = fileBytes > LargeFileAnonymizer.LARGE_FILE_THRESHOLD;

                            if (isLargeFile) {
                                // Use streaming approach for files > 2GB
                                // Note: Large file anonymization has limited verification support
                                log.info("Using streaming anonymization for large file: {} ({} GB)",
                                        inputFile.getFileName(),
                                        String.format("%.2f", fileBytes / (1024.0 * 1024.0 * 1024.0)));
                                largeFileAnonymizer.anonymizeLargeFile(inputFile, outputFile, applicator);
                            } else {
                                // Standard approach for normal-sized files
                                // Read original for verification
                                DicomObject originalDcm = null;
                                if (verificationEnabled) {
                                    try (DicomInputStream dis = new DicomInputStream(inputFile.toFile())) {
                                        originalDcm = dis.readDicomObject();
                                    }
                                }

                                // Read input DICOM for anonymization
                                DicomObject dcmObj;
                                try (DicomInputStream dis = new DicomInputStream(inputFile.toFile())) {
                                    dcmObj = dis.readDicomObject();
                                }

                                // Apply the script
                                applicator.apply(inputFile.toFile(), dcmObj);

                                // Verify BEFORE writing
                                if (verificationEnabled && originalDcm != null) {
                                    AnonymizationVerifier.VerificationConfig config = createVerificationConfig();
                                    AnonymizationVerifier.VerificationResult verifyResult =
                                            verifier.verify(originalDcm, dcmObj, config);

                                    if (!verifyResult.isAllPassed()) {
                                        log.error("ANONYMIZATION VERIFICATION FAILED for {}:\n{}",
                                                inputFile.getFileName(), verifyResult);
                                        throw new IOException("Verification failed: " +
                                                verifyResult.getFailedCount() + " checks failed");
                                    }
                                }

                                // Ensure output directory exists
                                Files.createDirectories(outputFile.getParent());

                                // Write output DICOM (only if verification passed)
                                try (DicomOutputStream dos = new DicomOutputStream(outputFile.toFile())) {
                                    dos.writeDicomFile(dcmObj);
                                }
                            }

                            outputCount.incrementAndGet();

                        } catch (Exception e) {
                            errorCount.incrementAndGet();
                            String fileSize = "unknown";
                            try {
                                long bytes = Files.size(inputFile);
                                fileSize = String.format("%.2f MB", bytes / (1024.0 * 1024.0));
                            } catch (IOException ignored) {}
                            log.error("Error anonymizing {} (size: {}): {} - {}",
                                    inputFile.getFileName(), fileSize,
                                    e.getClass().getSimpleName(),
                                    e.getMessage() != null ? e.getMessage() : "no message", e);
                        } catch (OutOfMemoryError e) {
                            errorCount.incrementAndGet();
                            String fileSize = "unknown";
                            try {
                                long bytes = Files.size(inputFile);
                                fileSize = String.format("%.2f MB", bytes / (1024.0 * 1024.0));
                            } catch (IOException ignored) {}
                            log.error("OutOfMemoryError processing {} (size: {}). File too large for current heap size.",
                                    inputFile.getFileName(), fileSize);
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
