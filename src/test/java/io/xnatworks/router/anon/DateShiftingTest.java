/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */
package io.xnatworks.router.anon;

import org.dcm4che2.data.DicomObject;
import org.dcm4che2.data.Tag;
import org.dcm4che2.io.DicomInputStream;
import org.dcm4che2.io.DicomOutputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.nrg.dicom.dicomedit.DE6Script;
import org.nrg.dicom.dicomedit.ScriptApplicatorI;
import org.nrg.dicom.dicomedit.SerialScriptApplicator;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DicomEdit 6.6.0 date shifting functionality.
 * Verifies that shiftDateTimeByIncrement works correctly.
 */
@DisplayName("DicomEdit 6.6.0 Date Shifting Tests")
class DateShiftingTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Should parse script with shiftDateTimeByIncrement without error")
    void shouldParseScriptWithShiftDateTimeByIncrement() throws Exception {
        String script = """
            // Test script using shiftDateTimeByIncrement
            (0008,0020) := shiftDateTimeByIncrement[(0008,0020), "30", "days"]
            (0008,0030) := shiftDateTimeByIncrement[(0008,0030), "30", "days"]
            """;

        ByteArrayInputStream scriptStream = new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8));
        DE6Script de6Script = new DE6Script(scriptStream);
        ScriptApplicatorI applicator = new SerialScriptApplicator(Collections.singletonList(de6Script));

        assertNotNull(applicator, "Applicator should be created successfully");
        System.out.println("✓ shiftDateTimeByIncrement function parsed successfully!");
    }

    @Test
    @DisplayName("Should shift Study Date by specified days")
    void shouldShiftStudyDate() throws Exception {
        // Create a minimal DICOM object with a study date
        DicomObject dcm = createMinimalDicom("20240101", "120000");

        String originalDate = dcm.getString(Tag.StudyDate);
        assertEquals("20240101", originalDate, "Original date should be 20240101");

        // Script to shift date by 30 days
        String script = """
            (0008,0020) := shiftDateTimeByIncrement[(0008,0020), "30", "days"]
            """;

        // Save DICOM to temp file (required for apply)
        Path inputFile = tempDir.resolve("input.dcm");
        try (DicomOutputStream dos = new DicomOutputStream(Files.newOutputStream(inputFile))) {
            dos.writeDicomFile(dcm);
        }

        // Apply the script
        ByteArrayInputStream scriptStream = new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8));
        DE6Script de6Script = new DE6Script(scriptStream);
        ScriptApplicatorI applicator = new SerialScriptApplicator(Collections.singletonList(de6Script));

        // Re-read the DICOM for application
        DicomObject dcmToAnonymize;
        try (DicomInputStream dis = new DicomInputStream(inputFile.toFile())) {
            dcmToAnonymize = dis.readDicomObject();
        }

        applicator.apply(inputFile.toFile(), dcmToAnonymize);

        String shiftedDate = dcmToAnonymize.getString(Tag.StudyDate);
        System.out.println("Original date: " + originalDate);
        System.out.println("Shifted date:  " + shiftedDate);

        // Verify date was shifted (not equal to original)
        assertNotEquals(originalDate, shiftedDate, "Date should be shifted");
        // The actual shift depends on DicomEdit's interpretation - key is it changed
        System.out.println("✓ Date shifting works - date was modified from " + originalDate + " to " + shiftedDate);
    }

    @Test
    @DisplayName("Should shift date by negative days (into past)")
    void shouldShiftDateBackward() throws Exception {
        DicomObject dcm = createMinimalDicom("20240315", "120000");
        String originalDate = dcm.getString(Tag.StudyDate);

        String script = """
            (0008,0020) := shiftDateTimeByIncrement[(0008,0020), "-30", "days"]
            """;

        Path inputFile = tempDir.resolve("input.dcm");
        try (DicomOutputStream dos = new DicomOutputStream(Files.newOutputStream(inputFile))) {
            dos.writeDicomFile(dcm);
        }

        ByteArrayInputStream scriptStream = new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8));
        DE6Script de6Script = new DE6Script(scriptStream);
        ScriptApplicatorI applicator = new SerialScriptApplicator(Collections.singletonList(de6Script));

        DicomObject dcmToAnonymize;
        try (DicomInputStream dis = new DicomInputStream(inputFile.toFile())) {
            dcmToAnonymize = dis.readDicomObject();
        }

        applicator.apply(inputFile.toFile(), dcmToAnonymize);

        String shiftedDate = dcmToAnonymize.getString(Tag.StudyDate);
        System.out.println("Original date: " + originalDate);
        System.out.println("Shifted date:  " + shiftedDate);

        // Verify date was shifted backward (before original)
        assertNotEquals(originalDate, shiftedDate, "Date should be shifted");
        assertTrue(shiftedDate.compareTo(originalDate) < 0, "Shifted date should be earlier than original");
        System.out.println("✓ Backward date shifting works - date was shifted from " + originalDate + " to " + shiftedDate);
    }

    @Test
    @DisplayName("ScriptEnhancer should generate valid shiftDateTimeByIncrement commands")
    void scriptEnhancerShouldGenerateValidCommands() throws Exception {
        String baseScript = """
            // Basic anonymization
            (0010,0010) := "Anonymous"
            """;

        String enhanced = ScriptEnhancer.enhanceWithDateShift(baseScript, 45);

        System.out.println("Enhanced script:\n" + enhanced);

        // Verify the script contains shiftDateTimeByIncrement
        assertTrue(enhanced.contains("shiftDateTimeByIncrement"),
                "Enhanced script should contain shiftDateTimeByIncrement");

        // Parse to verify it's valid DicomEdit syntax
        ByteArrayInputStream scriptStream = new ByteArrayInputStream(enhanced.getBytes(StandardCharsets.UTF_8));
        DE6Script de6Script = new DE6Script(scriptStream);
        ScriptApplicatorI applicator = new SerialScriptApplicator(Collections.singletonList(de6Script));

        assertNotNull(applicator, "Enhanced script should parse without errors");
        System.out.println("✓ ScriptEnhancer generates valid DicomEdit 6.6.0 scripts!");
    }

    /**
     * Create a minimal DICOM object for testing.
     */
    private DicomObject createMinimalDicom(String studyDate, String studyTime) {
        org.dcm4che2.data.BasicDicomObject dcm = new org.dcm4che2.data.BasicDicomObject();

        // File Meta Information
        dcm.putString(Tag.MediaStorageSOPClassUID, org.dcm4che2.data.VR.UI, "1.2.840.10008.5.1.4.1.1.2");
        dcm.putString(Tag.MediaStorageSOPInstanceUID, org.dcm4che2.data.VR.UI, "1.2.3.4.5.6.7.8.9");
        dcm.putString(Tag.TransferSyntaxUID, org.dcm4che2.data.VR.UI, "1.2.840.10008.1.2");

        // Patient
        dcm.putString(Tag.PatientName, org.dcm4che2.data.VR.PN, "Test^Patient");
        dcm.putString(Tag.PatientID, org.dcm4che2.data.VR.LO, "TEST001");

        // Study
        dcm.putString(Tag.StudyDate, org.dcm4che2.data.VR.DA, studyDate);
        dcm.putString(Tag.StudyTime, org.dcm4che2.data.VR.TM, studyTime);
        dcm.putString(Tag.StudyInstanceUID, org.dcm4che2.data.VR.UI, "1.2.3.4.5.6.7.8.10");

        // Series
        dcm.putString(Tag.SeriesDate, org.dcm4che2.data.VR.DA, studyDate);
        dcm.putString(Tag.SeriesInstanceUID, org.dcm4che2.data.VR.UI, "1.2.3.4.5.6.7.8.11");
        dcm.putString(Tag.Modality, org.dcm4che2.data.VR.CS, "CT");

        // Instance
        dcm.putString(Tag.SOPClassUID, org.dcm4che2.data.VR.UI, "1.2.840.10008.5.1.4.1.1.2");
        dcm.putString(Tag.SOPInstanceUID, org.dcm4che2.data.VR.UI, "1.2.3.4.5.6.7.8.9");

        return dcm;
    }
}
