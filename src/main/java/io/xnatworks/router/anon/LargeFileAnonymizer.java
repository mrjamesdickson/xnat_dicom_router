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
import org.dcm4che2.data.Tag;
import org.dcm4che2.data.TransferSyntax;
import org.dcm4che2.data.VR;
import org.dcm4che2.io.DicomInputStream;
import org.dcm4che2.io.DicomOutputStream;
import org.dcm4che2.io.StopTagInputHandler;
import org.nrg.dcm.edit.ScriptApplicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.Map;

/**
 * Handles anonymization of very large DICOM files (> 2GB) using a streaming approach.
 *
 * The standard dcm4che2 DicomInputStream.readDicomObject() method fails for files > 2GB
 * due to 32-bit integer limitations. This class works around the issue by:
 * 1. Reading only the DICOM metadata (header) using StopTagInputHandler to stop before PixelData
 * 2. Applying anonymization to the header only (PHI is never in pixel data for standard imaging)
 * 3. Writing a new file with the anonymized header + original pixel data copied byte-for-byte
 *
 * This approach is valid because DICOM anonymization only needs to modify metadata tags,
 * not the actual pixel data. The pixel data is simply copied unchanged.
 */
public class LargeFileAnonymizer {
    private static final Logger log = LoggerFactory.getLogger(LargeFileAnonymizer.class);

    // Files larger than this threshold (2GB) use streaming approach
    public static final long LARGE_FILE_THRESHOLD = 2L * 1024 * 1024 * 1024;

    // Buffer size for copying pixel data (64MB for fast streaming)
    private static final int COPY_BUFFER_SIZE = 64 * 1024 * 1024;

    // PixelData tag - where we stop reading metadata
    private static final int PIXEL_DATA_TAG = Tag.PixelData;

    /**
     * Check if a file should use the streaming approach.
     */
    public static boolean requiresStreamingApproach(Path file) throws IOException {
        return Files.size(file) > LARGE_FILE_THRESHOLD;
    }

    /**
     * Anonymize a large DICOM file using streaming approach.
     *
     * @param inputFile Input DICOM file (must be > 2GB)
     * @param outputFile Output DICOM file
     * @param applicator Pre-configured ScriptApplicator
     * @return true if successful
     */
    public boolean anonymizeLargeFile(Path inputFile, Path outputFile, ScriptApplicator applicator)
            throws IOException {
        long fileSize = Files.size(inputFile);
        log.info("Using streaming anonymization for large file: {} ({} GB)",
                inputFile.getFileName(), String.format("%.2f", fileSize / (1024.0 * 1024.0 * 1024.0)));

        // Step 1: Read only the header (metadata), stopping before PixelData
        DicomObject header;
        long pixelDataOffset;
        String transferSyntaxUID;

        try (RandomAccessFile raf = new RandomAccessFile(inputFile.toFile(), "r")) {
            try (DicomInputStream dis = new DicomInputStream(inputFile.toFile())) {
                // Set handler to stop at PixelData tag
                dis.setHandler(new StopTagInputHandler(PIXEL_DATA_TAG));

                // Read header only
                header = dis.readDicomObject();

                // Get the position where PixelData starts (or end of file if no PixelData)
                // The position after readDicomObject with StopTagInputHandler is where it stopped
                pixelDataOffset = dis.getStreamPosition();

                // Get transfer syntax for proper output
                transferSyntaxUID = dis.getTransferSyntax().uid();
            }
        }

        log.debug("Read header up to offset {} (header size: {} MB)",
                pixelDataOffset, String.format("%.2f", pixelDataOffset / (1024.0 * 1024.0)));

        // Step 2: Apply anonymization script to header
        try {
            applicator.apply(inputFile.toFile(), header);
        } catch (Exception e) {
            throw new IOException("Failed to apply anonymization script: " + e.getMessage(), e);
        }

        // Step 3: Write output file with anonymized header + original pixel data
        Files.createDirectories(outputFile.getParent());

        try (FileOutputStream fos = new FileOutputStream(outputFile.toFile());
             BufferedOutputStream bos = new BufferedOutputStream(fos, COPY_BUFFER_SIZE)) {

            // Write anonymized header
            DicomOutputStream dos = new DicomOutputStream(bos);
            dos.setAutoFinish(false);

            // Write DICOM preamble (128 bytes of zeros + "DICM")
            bos.write(new byte[128]);
            bos.write(new byte[] {'D', 'I', 'C', 'M'});

            // Write File Meta Information
            dos.writeFileMetaInformation(header);

            // Write Dataset (header elements, stopping before PixelData)
            TransferSyntax ts = TransferSyntax.valueOf(transferSyntaxUID);
            Iterator<DicomElement> iter = header.iterator();
            while (iter.hasNext()) {
                DicomElement elem = iter.next();
                int tag = elem.tag();
                // Skip group length tags (element 0x0000 in any group) and File Meta Information
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

            // Flush the buffered output
            bos.flush();

            // Step 4: Copy pixel data from original file
            try (FileChannel inChannel = FileChannel.open(inputFile, StandardOpenOption.READ)) {
                try (FileChannel outChannel = FileChannel.open(outputFile,
                        StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {

                    // Copy everything from pixelDataOffset to end of file
                    long remaining = inChannel.size() - pixelDataOffset;
                    if (remaining > 0) {
                        log.debug("Copying {} bytes of pixel data (starting at offset {})",
                                remaining, pixelDataOffset);

                        // Position input at pixel data start
                        inChannel.position(pixelDataOffset);

                        // Transfer using direct buffer copy (very fast)
                        long copied = 0;
                        ByteBuffer buffer = ByteBuffer.allocateDirect(COPY_BUFFER_SIZE);
                        while (copied < remaining) {
                            buffer.clear();
                            int bytesRead = inChannel.read(buffer);
                            if (bytesRead < 0) break;
                            buffer.flip();
                            while (buffer.hasRemaining()) {
                                copied += outChannel.write(buffer);
                            }
                        }

                        log.debug("Copied {} bytes of pixel data", copied);
                    }
                }
            }
        }

        // Verify output file size is reasonable (should be close to input size)
        long outputSize = Files.size(outputFile);
        double sizeDiff = Math.abs(outputSize - fileSize) / (double) fileSize;
        if (sizeDiff > 0.1) {  // More than 10% size difference is suspicious
            log.warn("Output file size ({}) differs significantly from input ({}) - {} difference",
                    outputSize, fileSize, String.format("%.1f%%", sizeDiff * 100));
        }

        log.info("Successfully anonymized large file: {} -> {} ({} GB)",
                inputFile.getFileName(), outputFile.getFileName(),
                String.format("%.2f", outputSize / (1024.0 * 1024.0 * 1024.0)));

        return true;
    }

    /**
     * Anonymize a large DICOM file using a script string.
     */
    public boolean anonymizeLargeFile(Path inputFile, Path outputFile, String script,
            Map<String, String> variables) throws IOException {
        // Create applicator from script
        ByteArrayInputStream scriptStream = new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8));
        ScriptApplicator applicator = new ScriptApplicator(scriptStream);

        // Set variables
        if (variables != null) {
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                applicator.setVariable(entry.getKey(), entry.getValue());
            }
        }

        return anonymizeLargeFile(inputFile, outputFile, applicator);
    }
}
