/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */
package io.xnatworks.router.anon;

import org.dcm4che2.data.DicomObject;
import org.dcm4che2.data.Tag;
import org.dcm4che2.data.BasicDicomObject;
import org.dcm4che2.data.VR;
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
 * Test in-place shift vs assignment syntax.
 */
@DisplayName("In-Place Shift Test")
class InPlaceShiftTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Test in-place shift (no assignment)")
    void testInPlaceShift() throws Exception {
        System.out.println("\n=== Testing In-Place Shift (no assignment) ===\n");

        // Create DICOM with known date
        BasicDicomObject dcm = createDicom("20240101");
        String originalDate = dcm.getString(Tag.StudyDate);
        System.out.println("Original StudyDate: " + originalDate);

        // Script WITHOUT assignment - just the function call
        String script = """
            shiftDateTimeByIncrement[(0008,0020), "30", "days"]
            """;

        System.out.println("Script (no assignment):\n" + script);

        String result = applyScript(dcm, script);
        System.out.println("Result StudyDate: " + result);
        System.out.println("Changed: " + !originalDate.equals(result));
    }

    @Test
    @DisplayName("Test assignment shift")
    void testAssignmentShift() throws Exception {
        System.out.println("\n=== Testing Assignment Shift ===\n");

        // Create DICOM with known date
        BasicDicomObject dcm = createDicom("20240101");
        String originalDate = dcm.getString(Tag.StudyDate);
        System.out.println("Original StudyDate: " + originalDate);

        // Script WITH assignment
        String script = """
            (0008,0020) := shiftDateTimeByIncrement[(0008,0020), "30", "days"]
            """;

        System.out.println("Script (with assignment):\n" + script);

        String result = applyScript(dcm, script);
        System.out.println("Result StudyDate: " + result);
        System.out.println("Changed: " + !originalDate.equals(result));
    }

    @Test
    @DisplayName("Compare both approaches")
    void compareBothApproaches() throws Exception {
        System.out.println("\n=== Comparing Both Approaches ===\n");

        String originalDate = "20240101";

        // Test 1: No assignment
        BasicDicomObject dcm1 = createDicom(originalDate);
        String script1 = "shiftDateTimeByIncrement[(0008,0020), \"30\", \"days\"]";
        String result1 = applyScript(dcm1, script1);

        // Test 2: With assignment
        BasicDicomObject dcm2 = createDicom(originalDate);
        String script2 = "(0008,0020) := shiftDateTimeByIncrement[(0008,0020), \"30\", \"days\"]";
        String result2 = applyScript(dcm2, script2);

        System.out.println("Original:              " + originalDate);
        System.out.println("No assignment result:  " + result1 + " (changed: " + !originalDate.equals(result1) + ")");
        System.out.println("With assignment result:" + result2 + " (changed: " + !originalDate.equals(result2) + ")");

        if (result1.equals(result2)) {
            System.out.println("\n✓ Both approaches produce the same result!");
        } else if (!originalDate.equals(result1) && !originalDate.equals(result2)) {
            System.out.println("\n⚠ Both shift but to different values!");
        } else if (originalDate.equals(result1)) {
            System.out.println("\n→ In-place (no assignment) does NOT modify the tag");
            System.out.println("→ Assignment syntax is required for the shift to take effect");
        }
    }

    @Test
    @DisplayName("Test various shift values to understand the actual shift")
    void testVariousShiftValues() throws Exception {
        System.out.println("\n=== Testing Various Shift Values ===\n");

        String originalDate = "20240115"; // Jan 15, 2024
        System.out.println("Original date: " + originalDate + " (January 15, 2024)\n");

        int[] shiftValues = {1, 7, 10, 15, 30, 60};
        for (int days : shiftValues) {
            BasicDicomObject dcm = createDicom(originalDate);
            String script = "(0008,0020) := shiftDateTimeByIncrement[(0008,0020), \"" + days + "\", \"days\"]";
            String result = applyScript(dcm, script);

            // Calculate expected using Java
            java.time.LocalDate orig = java.time.LocalDate.parse(originalDate, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            java.time.LocalDate expected = orig.plusDays(days);
            String expectedStr = expected.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);

            // Calculate actual days shifted
            java.time.LocalDate resultDate = java.time.LocalDate.parse(result, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            long actualDays = java.time.temporal.ChronoUnit.DAYS.between(orig, resultDate);

            System.out.printf("Shift by %2d days: got %s (actual shift: %+d days), expected %s %s%n",
                    days, result, actualDays, expectedStr, result.equals(expectedStr) ? "✓" : "✗");
        }
    }

    @Test
    @DisplayName("Test with literal date value instead of tag reference")
    void testWithLiteralValue() throws Exception {
        System.out.println("\n=== Testing With Literal Value (not tag reference) ===\n");

        String originalDate = "20240115";
        System.out.println("Original date in DICOM: " + originalDate + "\n");

        // Use literal string instead of tag reference
        BasicDicomObject dcm = createDicom(originalDate);
        String script = "(0008,0020) := shiftDateTimeByIncrement[\"20240115\", \"30\", \"days\"]";
        System.out.println("Script: " + script);
        String result = applyScript(dcm, script);

        java.time.LocalDate orig = java.time.LocalDate.parse(originalDate, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        java.time.LocalDate resultDate = java.time.LocalDate.parse(result, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        long actualDays = java.time.temporal.ChronoUnit.DAYS.between(orig, resultDate);

        System.out.println("Result: " + result + " (actual shift: " + actualDays + " days)");
        System.out.println("Expected: 20240214 (30 days from Jan 15)");

        // Now test with tag reference for comparison
        BasicDicomObject dcm2 = createDicom(originalDate);
        String script2 = "(0008,0020) := shiftDateTimeByIncrement[(0008,0020), \"30\", \"days\"]";
        System.out.println("\nScript with tag ref: " + script2);
        String result2 = applyScript(dcm2, script2);

        java.time.LocalDate resultDate2 = java.time.LocalDate.parse(result2, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        long actualDays2 = java.time.temporal.ChronoUnit.DAYS.between(orig, resultDate2);

        System.out.println("Result: " + result2 + " (actual shift: " + actualDays2 + " days)");

        if (actualDays == 30 && actualDays2 == 60) {
            System.out.println("\n→ CONFIRMED: Tag reference causes double application!");
            System.out.println("→ The tag is being read AND shifted, then shifted again on assignment");
        }
    }

    @Test
    @DisplayName("Test shiftDateByIncrement (date-only version)")
    void testShiftDateByIncrement() throws Exception {
        System.out.println("\n=== Testing shiftDateByIncrement (date-only) ===\n");

        String originalDate = "20240115";
        System.out.println("Original date in DICOM: " + originalDate + "\n");

        // Try shiftDateByIncrement instead of shiftDateTimeByIncrement
        BasicDicomObject dcm = createDicom(originalDate);
        String script = "(0008,0020) := shiftDateByIncrement[(0008,0020), \"30\", \"days\"]";
        System.out.println("Script: " + script);

        try {
            String result = applyScript(dcm, script);

            java.time.LocalDate orig = java.time.LocalDate.parse(originalDate, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            java.time.LocalDate resultDate = java.time.LocalDate.parse(result, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            long actualDays = java.time.temporal.ChronoUnit.DAYS.between(orig, resultDate);

            System.out.println("Result: " + result + " (actual shift: " + actualDays + " days)");
            System.out.println("Expected: 20240214 (30 days)");
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("Test ScriptEnhancer with corrected shift (divide by 2)")
    void testScriptEnhancerCorrectedShift() throws Exception {
        System.out.println("\n=== Testing ScriptEnhancer with Corrected Shift ===\n");

        String originalDate = "20240115";
        System.out.println("Original date in DICOM: " + originalDate);
        System.out.println("Requested shift: 30 days\n");

        // Generate script using ScriptEnhancer (which now divides by 2)
        String baseScript = "// Base script";
        String enhanced = ScriptEnhancer.enhanceWithDateShift(baseScript, 30);

        System.out.println("Generated script:\n" + enhanced);

        // Extract just the StudyDate line to test (other tags may not exist in test DICOM)
        String studyDateLine = "(0008,0020) := shiftDateTimeByIncrement[(0008,0020), \"15\", \"days\"]";
        assertTrue(enhanced.contains(studyDateLine), "Script should contain StudyDate with shift of 15");

        // Apply just the StudyDate shift
        BasicDicomObject dcm = createDicom(originalDate);
        String result = applyScript(dcm, studyDateLine);

        java.time.LocalDate orig = java.time.LocalDate.parse(originalDate, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        java.time.LocalDate resultDate = java.time.LocalDate.parse(result, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        long actualDays = java.time.temporal.ChronoUnit.DAYS.between(orig, resultDate);

        System.out.println("\nResult: " + result + " (actual shift: " + actualDays + " days)");
        System.out.println("Expected: 20240214 (30 days from Jan 15)");

        assertEquals(30, actualDays, "ScriptEnhancer should produce correct 30-day shift");
        System.out.println("\n✓ ScriptEnhancer correctly compensates for double-shift!");
    }

    private BasicDicomObject createDicom(String studyDate) {
        BasicDicomObject dcm = new BasicDicomObject();
        dcm.putString(Tag.MediaStorageSOPClassUID, VR.UI, "1.2.840.10008.5.1.4.1.1.2");
        dcm.putString(Tag.MediaStorageSOPInstanceUID, VR.UI, "1.2.3.4.5.6.7.8.9");
        dcm.putString(Tag.TransferSyntaxUID, VR.UI, "1.2.840.10008.1.2");
        dcm.putString(Tag.PatientName, VR.PN, "Test^Patient");
        dcm.putString(Tag.PatientID, VR.LO, "TEST001");
        dcm.putString(Tag.StudyDate, VR.DA, studyDate);
        dcm.putString(Tag.StudyTime, VR.TM, "120000");
        dcm.putString(Tag.StudyInstanceUID, VR.UI, "1.2.3.4.5.6.7.8.10");
        dcm.putString(Tag.SeriesInstanceUID, VR.UI, "1.2.3.4.5.6.7.8.11");
        dcm.putString(Tag.Modality, VR.CS, "CT");
        dcm.putString(Tag.SOPClassUID, VR.UI, "1.2.840.10008.5.1.4.1.1.2");
        dcm.putString(Tag.SOPInstanceUID, VR.UI, "1.2.3.4.5.6.7.8.9");
        return dcm;
    }

    private String applyScript(DicomObject dcm, String script) throws Exception {
        // Save to temp file
        Path inputFile = tempDir.resolve("test_" + System.nanoTime() + ".dcm");
        try (DicomOutputStream dos = new DicomOutputStream(Files.newOutputStream(inputFile))) {
            dos.writeDicomFile(dcm);
        }

        // Parse and apply script
        ByteArrayInputStream scriptStream = new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8));
        DE6Script de6Script = new DE6Script(scriptStream);
        ScriptApplicatorI applicator = new SerialScriptApplicator(Collections.singletonList(de6Script));

        DicomObject dcmToAnonymize;
        try (DicomInputStream dis = new DicomInputStream(inputFile.toFile())) {
            dcmToAnonymize = dis.readDicomObject();
        }

        applicator.apply(inputFile.toFile(), dcmToAnonymize);

        return dcmToAnonymize.getString(Tag.StudyDate);
    }
}
