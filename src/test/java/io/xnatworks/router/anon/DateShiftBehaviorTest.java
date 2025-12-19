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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests to understand the exact behavior of shiftDateTimeByIncrement.
 */
@DisplayName("Date Shift Behavior Investigation")
class DateShiftBehaviorTest {

    @TempDir
    Path tempDir;

    private static final DateTimeFormatter FMT = DateTimeFormatter.BASIC_ISO_DATE;

    @Test
    @DisplayName("Investigate shiftDateTimeByIncrement behavior with various values")
    void investigateShiftBehavior() throws Exception {
        System.out.println("\n=== Investigating shiftDateTimeByIncrement Behavior ===\n");

        String[] testDates = {"20240101", "20240315", "20231215"};
        int[] shiftDays = {1, 7, 30, 60, 90};

        for (String origDate : testDates) {
            System.out.println("Original Date: " + origDate);
            for (int days : shiftDays) {
                String shifted = shiftDate(origDate, days);
                LocalDate orig = LocalDate.parse(origDate, FMT);
                LocalDate result = LocalDate.parse(shifted, FMT);
                long actualDays = java.time.temporal.ChronoUnit.DAYS.between(orig, result);

                System.out.printf("  Shift by %3d days -> %s (actual shift: %+d days)%n",
                        days, shifted, actualDays);
            }
            System.out.println();
        }
    }

    @Test
    @DisplayName("Test negative shifts")
    void testNegativeShifts() throws Exception {
        System.out.println("\n=== Testing Negative Shifts ===\n");

        String origDate = "20240315";
        int[] shiftDays = {-1, -7, -30, -60, -90};

        System.out.println("Original Date: " + origDate);
        for (int days : shiftDays) {
            String shifted = shiftDate(origDate, days);
            LocalDate orig = LocalDate.parse(origDate, FMT);
            LocalDate result = LocalDate.parse(shifted, FMT);
            long actualDays = java.time.temporal.ChronoUnit.DAYS.between(orig, result);

            System.out.printf("  Shift by %4d days -> %s (actual shift: %+d days)%n",
                    days, shifted, actualDays);
        }
    }

    private String shiftDate(String originalDate, int shiftDays) throws Exception {
        // Create DICOM with the original date
        BasicDicomObject dcm = new BasicDicomObject();
        dcm.putString(Tag.MediaStorageSOPClassUID, VR.UI, "1.2.840.10008.5.1.4.1.1.2");
        dcm.putString(Tag.MediaStorageSOPInstanceUID, VR.UI, "1.2.3.4.5.6.7.8.9");
        dcm.putString(Tag.TransferSyntaxUID, VR.UI, "1.2.840.10008.1.2");
        dcm.putString(Tag.PatientName, VR.PN, "Test^Patient");
        dcm.putString(Tag.PatientID, VR.LO, "TEST001");
        dcm.putString(Tag.StudyDate, VR.DA, originalDate);
        dcm.putString(Tag.StudyTime, VR.TM, "120000");
        dcm.putString(Tag.StudyInstanceUID, VR.UI, "1.2.3.4.5.6.7.8.10");
        dcm.putString(Tag.SeriesInstanceUID, VR.UI, "1.2.3.4.5.6.7.8.11");
        dcm.putString(Tag.Modality, VR.CS, "CT");
        dcm.putString(Tag.SOPClassUID, VR.UI, "1.2.840.10008.5.1.4.1.1.2");
        dcm.putString(Tag.SOPInstanceUID, VR.UI, "1.2.3.4.5.6.7.8.9");

        // Save to temp file
        Path inputFile = tempDir.resolve("test_" + originalDate + "_" + shiftDays + ".dcm");
        try (DicomOutputStream dos = new DicomOutputStream(Files.newOutputStream(inputFile))) {
            dos.writeDicomFile(dcm);
        }

        // Create script
        String script = "(0008,0020) := shiftDateTimeByIncrement[(0008,0020), \"" + shiftDays + "\", \"days\"]";

        // Apply script
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
