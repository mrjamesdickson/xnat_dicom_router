/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.anon;

import org.dcm4che2.data.DicomObject;
import org.dcm4che2.data.Tag;
import org.dcm4che2.data.TransferSyntax;
import org.dcm4che2.data.DicomElement;
import org.dcm4che2.io.DicomInputStream;
import org.dcm4che2.io.DicomOutputStream;
import org.dcm4che2.io.StopTagInputHandler;
import org.nrg.dcm.edit.ScriptApplicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Streaming anonymizer that writes directly to a ZIP file without intermediate temp directories.
 * This dramatically reduces disk usage for large studies from 4x to 2x (incoming + ZIP only).
 *
 * For very large files (> 2GB), uses the same streaming approach as LargeFileAnonymizer
 * but writes directly to the ZIP stream.
 */
public class StreamingAnonymizer {
    private static final Logger log = LoggerFactory.getLogger(StreamingAnonymizer.class);

    // Files larger than this threshold (2GB) use streaming approach for header/pixel separation
    public static final long LARGE_FILE_THRESHOLD = 2L * 1024 * 1024 * 1024;

    // Buffer size for copying data (64MB)
    private static final int COPY_BUFFER_SIZE = 64 * 1024 * 1024;

    /**
     * Callback interface for capturing UID mappings during anonymization.
     * Called once for each unique UID encountered.
     */
    public interface UidMappingCallback {
        /**
         * Called when a UID is mapped from original to anonymized value.
         *
         * @param originalUid The original UID value
         * @param anonymizedUid The anonymized/hashed UID value
         * @param uidType Type: "study_uid", "series_uid", or "sop_uid"
         */
        void onUidMapping(String originalUid, String anonymizedUid, String uidType);
    }

    /**
     * Result of streaming anonymization to ZIP.
     */
    public static class StreamingResult {
        private final int totalFiles;
        private final int successFiles;
        private final int errorFiles;
        private final long totalBytes;
        private final long durationMs;

        public StreamingResult(int totalFiles, int successFiles, int errorFiles, long totalBytes, long durationMs) {
            this.totalFiles = totalFiles;
            this.successFiles = successFiles;
            this.errorFiles = errorFiles;
            this.totalBytes = totalBytes;
            this.durationMs = durationMs;
        }

        public int getTotalFiles() { return totalFiles; }
        public int getSuccessFiles() { return successFiles; }
        public int getErrorFiles() { return errorFiles; }
        public long getTotalBytes() { return totalBytes; }
        public long getDurationMs() { return durationMs; }
        public boolean isSuccess() { return errorFiles == 0; }
    }

    /**
     * Anonymize files and write directly to a ZIP file.
     * No intermediate temp directories are created.
     *
     * @param inputFiles List of DICOM files to anonymize
     * @param zipFile Output ZIP file path
     * @param script DicomEdit script content
     * @param variables Script variables
     * @return Result containing counts of processed files
     */
    public StreamingResult anonymizeToZip(List<File> inputFiles, Path zipFile, String script,
                                          Map<String, String> variables) throws IOException {
        long startTime = System.currentTimeMillis();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        AtomicInteger totalBytes = new AtomicInteger(0);

        // Create script applicator
        ByteArrayInputStream scriptStream = new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8));
        ScriptApplicator applicator = new ScriptApplicator(scriptStream);
        if (variables != null) {
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                applicator.setVariable(entry.getKey(), entry.getValue());
            }
        }

        // Create ZIP output stream
        try (FileOutputStream fos = new FileOutputStream(zipFile.toFile());
             BufferedOutputStream bos = new BufferedOutputStream(fos, COPY_BUFFER_SIZE);
             ZipOutputStream zos = new ZipOutputStream(bos)) {

            zos.setLevel(0); // No compression for DICOM (already compressed or incompressible)

            for (File inputFile : inputFiles) {
                try {
                    long fileSize = inputFile.length();

                    if (fileSize > LARGE_FILE_THRESHOLD) {
                        // Large file: use streaming header/pixel separation
                        anonymizeLargeFileToZip(inputFile, zos, applicator);
                    } else {
                        // Normal file: load into memory, anonymize, write to ZIP
                        anonymizeNormalFileToZip(inputFile, zos, applicator);
                    }

                    successCount.incrementAndGet();
                    totalBytes.addAndGet((int) Math.min(fileSize, Integer.MAX_VALUE));

                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    log.error("Error anonymizing {} to ZIP: {} - {}",
                            inputFile.getName(), e.getClass().getSimpleName(), e.getMessage());
                } catch (OutOfMemoryError e) {
                    errorCount.incrementAndGet();
                    log.error("OutOfMemoryError processing {} (size: {} MB). Consider using larger heap.",
                            inputFile.getName(), inputFile.length() / (1024.0 * 1024.0));
                }
            }
        }

        long durationMs = System.currentTimeMillis() - startTime;

        log.info("Streaming anonymization complete: {} -> {} files ({} errors) in {}ms",
                inputFiles.size(), successCount.get(), errorCount.get(), durationMs);

        return new StreamingResult(
                inputFiles.size(),
                successCount.get(),
                errorCount.get(),
                totalBytes.get(),
                durationMs
        );
    }

    /**
     * Anonymize files and write directly to a ZIP file, with UID mapping callback.
     * The callback is called for each unique UID encountered, allowing storage in crosswalk.
     *
     * @param inputFiles List of DICOM files to anonymize
     * @param zipFile Output ZIP file path
     * @param script DicomEdit script content
     * @param variables Script variables
     * @param uidCallback Callback for UID mappings (may be null)
     * @return Result containing counts of processed files
     */
    public StreamingResult anonymizeToZip(List<File> inputFiles, Path zipFile, String script,
                                          Map<String, String> variables,
                                          UidMappingCallback uidCallback) throws IOException {
        long startTime = System.currentTimeMillis();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        AtomicInteger totalBytes = new AtomicInteger(0);

        // Track already-reported UIDs to avoid duplicates
        Map<String, String> reportedStudyUids = new HashMap<>();
        Map<String, String> reportedSeriesUids = new HashMap<>();
        Map<String, String> reportedSopUids = new HashMap<>();

        // Create script applicator
        ByteArrayInputStream scriptStream = new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8));
        ScriptApplicator applicator = new ScriptApplicator(scriptStream);
        if (variables != null) {
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                applicator.setVariable(entry.getKey(), entry.getValue());
            }
        }

        // Create ZIP output stream
        try (FileOutputStream fos = new FileOutputStream(zipFile.toFile());
             BufferedOutputStream bos = new BufferedOutputStream(fos, COPY_BUFFER_SIZE);
             ZipOutputStream zos = new ZipOutputStream(bos)) {

            zos.setLevel(0); // No compression for DICOM

            for (File inputFile : inputFiles) {
                try {
                    long fileSize = inputFile.length();

                    if (fileSize > LARGE_FILE_THRESHOLD) {
                        anonymizeLargeFileToZipWithCallback(inputFile, zos, applicator,
                                uidCallback, reportedStudyUids, reportedSeriesUids, reportedSopUids);
                    } else {
                        anonymizeNormalFileToZipWithCallback(inputFile, zos, applicator,
                                uidCallback, reportedStudyUids, reportedSeriesUids, reportedSopUids);
                    }

                    successCount.incrementAndGet();
                    totalBytes.addAndGet((int) Math.min(fileSize, Integer.MAX_VALUE));

                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    log.error("Error anonymizing {} to ZIP: {} - {}",
                            inputFile.getName(), e.getClass().getSimpleName(), e.getMessage());
                } catch (OutOfMemoryError e) {
                    errorCount.incrementAndGet();
                    log.error("OutOfMemoryError processing {} (size: {} MB). Consider using larger heap.",
                            inputFile.getName(), inputFile.length() / (1024.0 * 1024.0));
                }
            }
        }

        long durationMs = System.currentTimeMillis() - startTime;

        log.info("Streaming anonymization complete: {} -> {} files ({} errors) in {}ms, {} UID mappings captured",
                inputFiles.size(), successCount.get(), errorCount.get(), durationMs,
                reportedStudyUids.size() + reportedSeriesUids.size() + reportedSopUids.size());

        return new StreamingResult(
                inputFiles.size(),
                successCount.get(),
                errorCount.get(),
                totalBytes.get(),
                durationMs
        );
    }

    /**
     * Anonymize a normal-sized file and write to ZIP.
     */
    private void anonymizeNormalFileToZip(File inputFile, ZipOutputStream zos,
                                          ScriptApplicator applicator) throws Exception {
        anonymizeNormalFileToZipWithCallback(inputFile, zos, applicator, null, null, null, null);
    }

    /**
     * Anonymize a normal-sized file and write to ZIP, with UID callback.
     */
    private void anonymizeNormalFileToZipWithCallback(File inputFile, ZipOutputStream zos,
                                                      ScriptApplicator applicator,
                                                      UidMappingCallback uidCallback,
                                                      Map<String, String> reportedStudyUids,
                                                      Map<String, String> reportedSeriesUids,
                                                      Map<String, String> reportedSopUids) throws Exception {
        // Read DICOM object
        DicomObject dcmObj;
        try (DicomInputStream dis = new DicomInputStream(inputFile)) {
            dcmObj = dis.readDicomObject();
        }

        // Capture original UIDs before anonymization
        String origStudyUid = dcmObj.getString(Tag.StudyInstanceUID);
        String origSeriesUid = dcmObj.getString(Tag.SeriesInstanceUID);
        String origSopUid = dcmObj.getString(Tag.SOPInstanceUID);

        // Apply anonymization
        applicator.apply(inputFile, dcmObj);

        // Capture new UIDs after anonymization and report to callback
        if (uidCallback != null) {
            String newStudyUid = dcmObj.getString(Tag.StudyInstanceUID);
            String newSeriesUid = dcmObj.getString(Tag.SeriesInstanceUID);
            String newSopUid = dcmObj.getString(Tag.SOPInstanceUID);

            // Report study UID mapping (once per unique original UID)
            if (origStudyUid != null && newStudyUid != null && !origStudyUid.equals(newStudyUid)) {
                if (reportedStudyUids == null || !reportedStudyUids.containsKey(origStudyUid)) {
                    uidCallback.onUidMapping(origStudyUid, newStudyUid, "study_uid");
                    if (reportedStudyUids != null) {
                        reportedStudyUids.put(origStudyUid, newStudyUid);
                    }
                }
            }

            // Report series UID mapping
            if (origSeriesUid != null && newSeriesUid != null && !origSeriesUid.equals(newSeriesUid)) {
                if (reportedSeriesUids == null || !reportedSeriesUids.containsKey(origSeriesUid)) {
                    uidCallback.onUidMapping(origSeriesUid, newSeriesUid, "series_uid");
                    if (reportedSeriesUids != null) {
                        reportedSeriesUids.put(origSeriesUid, newSeriesUid);
                    }
                }
            }

            // Report SOP UID mapping (every instance is unique)
            if (origSopUid != null && newSopUid != null && !origSopUid.equals(newSopUid)) {
                if (reportedSopUids == null || !reportedSopUids.containsKey(origSopUid)) {
                    uidCallback.onUidMapping(origSopUid, newSopUid, "sop_uid");
                    if (reportedSopUids != null) {
                        reportedSopUids.put(origSopUid, newSopUid);
                    }
                }
            }
        }

        // Write to ZIP entry
        ZipEntry entry = new ZipEntry(inputFile.getName());
        zos.putNextEntry(entry);

        // Write DICOM to ZIP stream
        DicomOutputStream dos = new DicomOutputStream(zos);
        dos.writeDicomFile(dcmObj);

        zos.closeEntry();
    }

    /**
     * Anonymize a large file (>2GB) using streaming and write to ZIP.
     * Only reads header into memory, streams pixel data directly.
     */
    private void anonymizeLargeFileToZip(File inputFile, ZipOutputStream zos,
                                         ScriptApplicator applicator) throws Exception {
        anonymizeLargeFileToZipWithCallback(inputFile, zos, applicator, null, null, null, null);
    }

    /**
     * Anonymize a large file with UID callback support.
     */
    private void anonymizeLargeFileToZipWithCallback(File inputFile, ZipOutputStream zos,
                                                     ScriptApplicator applicator,
                                                     UidMappingCallback uidCallback,
                                                     Map<String, String> reportedStudyUids,
                                                     Map<String, String> reportedSeriesUids,
                                                     Map<String, String> reportedSopUids) throws Exception {
        log.info("Using streaming anonymization for large file: {} ({} GB)",
                inputFile.getName(), String.format("%.2f", inputFile.length() / (1024.0 * 1024.0 * 1024.0)));

        // Step 1: Read only the header
        DicomObject header;
        long pixelDataOffset;
        String transferSyntaxUID;

        try (DicomInputStream dis = new DicomInputStream(inputFile)) {
            dis.setHandler(new StopTagInputHandler(Tag.PixelData));
            header = dis.readDicomObject();
            pixelDataOffset = dis.getStreamPosition();
            transferSyntaxUID = dis.getTransferSyntax().uid();
        }

        // Capture original UIDs before anonymization
        String origStudyUid = header.getString(Tag.StudyInstanceUID);
        String origSeriesUid = header.getString(Tag.SeriesInstanceUID);
        String origSopUid = header.getString(Tag.SOPInstanceUID);

        // Step 2: Apply anonymization to header only
        applicator.apply(inputFile, header);

        // Capture new UIDs after anonymization and report to callback
        if (uidCallback != null) {
            String newStudyUid = header.getString(Tag.StudyInstanceUID);
            String newSeriesUid = header.getString(Tag.SeriesInstanceUID);
            String newSopUid = header.getString(Tag.SOPInstanceUID);

            if (origStudyUid != null && newStudyUid != null && !origStudyUid.equals(newStudyUid)) {
                if (reportedStudyUids == null || !reportedStudyUids.containsKey(origStudyUid)) {
                    uidCallback.onUidMapping(origStudyUid, newStudyUid, "study_uid");
                    if (reportedStudyUids != null) {
                        reportedStudyUids.put(origStudyUid, newStudyUid);
                    }
                }
            }

            if (origSeriesUid != null && newSeriesUid != null && !origSeriesUid.equals(newSeriesUid)) {
                if (reportedSeriesUids == null || !reportedSeriesUids.containsKey(origSeriesUid)) {
                    uidCallback.onUidMapping(origSeriesUid, newSeriesUid, "series_uid");
                    if (reportedSeriesUids != null) {
                        reportedSeriesUids.put(origSeriesUid, newSeriesUid);
                    }
                }
            }

            if (origSopUid != null && newSopUid != null && !origSopUid.equals(newSopUid)) {
                if (reportedSopUids == null || !reportedSopUids.containsKey(origSopUid)) {
                    uidCallback.onUidMapping(origSopUid, newSopUid, "sop_uid");
                    if (reportedSopUids != null) {
                        reportedSopUids.put(origSopUid, newSopUid);
                    }
                }
            }
        }

        // Step 3: Write to ZIP entry
        ZipEntry entry = new ZipEntry(inputFile.getName());
        zos.putNextEntry(entry);

        // Write DICOM preamble
        zos.write(new byte[128]);
        zos.write(new byte[] {'D', 'I', 'C', 'M'});

        // Create DicomOutputStream wrapping the ZipOutputStream
        DicomOutputStream dos = new DicomOutputStream(zos);
        dos.setAutoFinish(false);

        // Write File Meta Information
        dos.writeFileMetaInformation(header);

        // Write Dataset (header elements, stopping before PixelData)
        TransferSyntax ts = TransferSyntax.valueOf(transferSyntaxUID);
        Iterator<DicomElement> iter = header.iterator();
        while (iter.hasNext()) {
            DicomElement elem = iter.next();
            int tag = elem.tag();
            // Skip group length tags and File Meta Information
            int element = tag & 0x0000FFFF;
            int group = (tag >> 16) & 0x0000FFFF;
            if (element == 0x0000 || group == 0x0002) {
                continue;
            }
            // Skip PixelData - we'll copy it from original
            if (tag == Tag.PixelData) {
                continue;
            }
            // Stop when we reach or pass PixelData tag
            if (tag > Tag.PixelData) {
                break;
            }
            dos.writeHeader(tag, elem.vr(), elem.length());
            if (elem.length() > 0) {
                dos.write(elem.getBytes());
            }
        }

        // Step 4: Copy pixel data from original file directly to ZIP
        try (FileChannel inChannel = FileChannel.open(inputFile.toPath(), StandardOpenOption.READ)) {
            long remaining = inChannel.size() - pixelDataOffset;
            if (remaining > 0) {
                log.debug("Copying {} bytes of pixel data to ZIP", remaining);

                inChannel.position(pixelDataOffset);

                // Use WritableByteChannel wrapper around ZipOutputStream
                WritableByteChannel outChannel = Channels.newChannel(zos);
                ByteBuffer buffer = ByteBuffer.allocateDirect(COPY_BUFFER_SIZE);

                long copied = 0;
                while (copied < remaining) {
                    buffer.clear();
                    int bytesRead = inChannel.read(buffer);
                    if (bytesRead < 0) break;
                    buffer.flip();
                    while (buffer.hasRemaining()) {
                        copied += outChannel.write(buffer);
                    }
                }

                log.debug("Copied {} bytes of pixel data to ZIP", copied);
            }
        }

        zos.closeEntry();
    }
}
