/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */
package io.xnatworks.router.anon;

import org.dcm4che2.data.BasicDicomObject;
import org.dcm4che2.data.DicomObject;
import org.dcm4che2.data.Tag;
import org.dcm4che2.data.VR;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AnonymizationVerifier.
 */
@DisplayName("Anonymization Verifier Tests")
class AnonymizationVerifierTest {

    private AnonymizationVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new AnonymizationVerifier();
    }

    @Nested
    @DisplayName("UID Change Verification")
    class UidChangeTests {

        @Test
        @DisplayName("Should pass when UIDs are changed")
        void shouldPassWhenUidsChanged() {
            DicomObject original = createDicom(
                    "John^Doe", "12345",
                    "1.2.3.4.5.6.7.8.9", "1.2.3.4.5.6.7.8.10", "1.2.3.4.5.6.7.8.11",
                    "20240115", "20240115", "19800101");

            DicomObject anonymized = createDicom(
                    "Anonymous", "ANON001",
                    "2.16.124.1.2.3", "2.16.124.1.2.4", "2.16.124.1.2.5",  // Changed UIDs
                    "20240115", "20240115", "19800101");

            AnonymizationVerifier.VerificationConfig config = new AnonymizationVerifier.VerificationConfig();
            config.setVerifyDatesShifted(false);

            AnonymizationVerifier.VerificationResult result = verifier.verify(original, anonymized, config);

            System.out.println(result);
            assertTrue(result.isAllPassed(), "Should pass when UIDs are changed");
        }

        @Test
        @DisplayName("Should FAIL when UIDs are NOT changed")
        void shouldFailWhenUidsNotChanged() {
            String sameStudyUid = "1.2.3.4.5.6.7.8.9";

            DicomObject original = createDicom(
                    "John^Doe", "12345",
                    sameStudyUid, "1.2.3.4.5.6.7.8.10", "1.2.3.4.5.6.7.8.11",
                    "20240115", "20240115", "19800101");

            DicomObject anonymized = createDicom(
                    "Anonymous", "ANON001",
                    sameStudyUid, "2.16.124.1.2.4", "2.16.124.1.2.5",  // Study UID NOT changed!
                    "20240115", "20240115", "19800101");

            AnonymizationVerifier.VerificationConfig config = new AnonymizationVerifier.VerificationConfig();
            config.setVerifyDatesShifted(false);

            AnonymizationVerifier.VerificationResult result = verifier.verify(original, anonymized, config);

            System.out.println(result);
            assertFalse(result.isAllPassed(), "Should FAIL when Study UID is not changed");
            assertTrue(result.getFailedCount() >= 1, "Should have at least one failed check");
        }
    }

    @Nested
    @DisplayName("Patient Info Verification")
    class PatientInfoTests {

        @Test
        @DisplayName("Should pass when patient name is changed")
        void shouldPassWhenPatientNameChanged() {
            DicomObject original = createDicom(
                    "John^Doe", "12345",
                    "1.2.3.4.5", "1.2.3.4.6", "1.2.3.4.7",
                    "20240115", "20240115", "19800101");

            DicomObject anonymized = createDicom(
                    "Anonymous", "ANON001",  // Name and ID changed
                    "2.16.124.1", "2.16.124.2", "2.16.124.3",
                    "20240115", "20240115", "19800101");

            AnonymizationVerifier.VerificationConfig config = new AnonymizationVerifier.VerificationConfig();
            config.setVerifyDatesShifted(false);

            AnonymizationVerifier.VerificationResult result = verifier.verify(original, anonymized, config);

            System.out.println(result);
            assertTrue(result.isAllPassed(), "Should pass when patient name is anonymized");
        }

        @Test
        @DisplayName("Should FAIL when patient name is NOT changed")
        void shouldFailWhenPatientNameNotChanged() {
            String sameName = "John^Doe";

            DicomObject original = createDicom(
                    sameName, "12345",
                    "1.2.3.4.5", "1.2.3.4.6", "1.2.3.4.7",
                    "20240115", "20240115", "19800101");

            DicomObject anonymized = createDicom(
                    sameName, "ANON001",  // Name NOT changed!
                    "2.16.124.1", "2.16.124.2", "2.16.124.3",
                    "20240115", "20240115", "19800101");

            AnonymizationVerifier.VerificationConfig config = new AnonymizationVerifier.VerificationConfig();
            config.setVerifyDatesShifted(false);

            AnonymizationVerifier.VerificationResult result = verifier.verify(original, anonymized, config);

            System.out.println(result);
            assertFalse(result.isAllPassed(), "Should FAIL when patient name is not changed");
        }
    }

    @Nested
    @DisplayName("Date Shift Verification")
    class DateShiftTests {

        @Test
        @DisplayName("Should pass when dates are shifted correctly")
        void shouldPassWhenDatesShiftedCorrectly() {
            // Jan 15, 2024 + 30 days = Feb 14, 2024
            // Jan 1, 1980 + 30 days = Jan 31, 1980
            DicomObject original = createDicom(
                    "John^Doe", "12345",
                    "1.2.3.4.5", "1.2.3.4.6", "1.2.3.4.7",
                    "20240115", "20240115", "19800101");

            DicomObject anonymized = createDicom(
                    "Anonymous", "ANON001",
                    "2.16.124.1", "2.16.124.2", "2.16.124.3",
                    "20240214", "20240214", "19800131");  // All +30 days

            AnonymizationVerifier.VerificationConfig config = new AnonymizationVerifier.VerificationConfig();
            config.setExpectedDateShiftDays(30);

            AnonymizationVerifier.VerificationResult result = verifier.verify(original, anonymized, config);

            System.out.println(result);
            assertTrue(result.isAllPassed(), "Should pass when dates are shifted by expected amount");
        }

        @Test
        @DisplayName("Should FAIL when dates are shifted incorrectly")
        void shouldFailWhenDatesShiftedIncorrectly() {
            DicomObject original = createDicom(
                    "John^Doe", "12345",
                    "1.2.3.4.5", "1.2.3.4.6", "1.2.3.4.7",
                    "20240115", "20240115", "19800101");

            DicomObject anonymized = createDicom(
                    "Anonymous", "ANON001",
                    "2.16.124.1", "2.16.124.2", "2.16.124.3",
                    "20240315", "20240315", "19800501");  // March 15, 2024 (+60 days, not +30!)

            AnonymizationVerifier.VerificationConfig config = new AnonymizationVerifier.VerificationConfig();
            config.setExpectedDateShiftDays(30);  // Expecting 30 days

            AnonymizationVerifier.VerificationResult result = verifier.verify(original, anonymized, config);

            System.out.println(result);
            assertFalse(result.isAllPassed(), "Should FAIL when dates are shifted by wrong amount");

            // Verify the error message mentions the discrepancy
            boolean foundDateError = false;
            for (AnonymizationVerifier.CheckResult check : result.getFailedChecks()) {
                if (check.getType() == AnonymizationVerifier.CheckType.DATE_SHIFT) {
                    foundDateError = true;
                    System.out.println("Date shift error: " + check);
                }
            }
            assertTrue(foundDateError, "Should have date shift error");
        }

        @Test
        @DisplayName("Should FAIL when dates are not shifted at all")
        void shouldFailWhenDatesNotShifted() {
            DicomObject original = createDicom(
                    "John^Doe", "12345",
                    "1.2.3.4.5", "1.2.3.4.6", "1.2.3.4.7",
                    "20240115", "20240115", "19800101");

            DicomObject anonymized = createDicom(
                    "Anonymous", "ANON001",
                    "2.16.124.1", "2.16.124.2", "2.16.124.3",
                    "20240115", "20240115", "19800101");  // Dates NOT shifted!

            AnonymizationVerifier.VerificationConfig config = new AnonymizationVerifier.VerificationConfig();
            config.setExpectedDateShiftDays(30);

            AnonymizationVerifier.VerificationResult result = verifier.verify(original, anonymized, config);

            System.out.println(result);
            assertFalse(result.isAllPassed(), "Should FAIL when dates are not shifted");
        }
    }

    @Nested
    @DisplayName("Exception Handling")
    class ExceptionTests {

        @Test
        @DisplayName("verifyOrThrow should throw on failure")
        void verifyOrThrowShouldThrowOnFailure() {
            DicomObject original = createDicom(
                    "John^Doe", "12345",
                    "1.2.3.4.5", "1.2.3.4.6", "1.2.3.4.7",
                    "20240115", "20240115", "19800101");

            DicomObject anonymized = createDicom(
                    "John^Doe", "12345",  // Not changed!
                    "1.2.3.4.5", "1.2.3.4.6", "1.2.3.4.7",  // Not changed!
                    "20240115", "20240115", "19800101");  // Not changed!

            AnonymizationVerifier.VerificationConfig config = new AnonymizationVerifier.VerificationConfig();
            config.setExpectedDateShiftDays(30);

            assertThrows(AnonymizationVerifier.AnonymizationVerificationException.class, () -> {
                verifier.verifyOrThrow(original, anonymized, config);
            });
        }
    }

    /**
     * Helper to create a DICOM object with specified values.
     */
    private DicomObject createDicom(String patientName, String patientId,
                                     String studyUid, String seriesUid, String sopUid,
                                     String studyDate, String seriesDate, String birthDate) {
        BasicDicomObject dcm = new BasicDicomObject();

        // File Meta
        dcm.putString(Tag.MediaStorageSOPClassUID, VR.UI, "1.2.840.10008.5.1.4.1.1.2");
        dcm.putString(Tag.MediaStorageSOPInstanceUID, VR.UI, sopUid);
        dcm.putString(Tag.TransferSyntaxUID, VR.UI, "1.2.840.10008.1.2");

        // Patient
        dcm.putString(Tag.PatientName, VR.PN, patientName);
        dcm.putString(Tag.PatientID, VR.LO, patientId);
        dcm.putString(Tag.PatientBirthDate, VR.DA, birthDate);

        // Study
        dcm.putString(Tag.StudyDate, VR.DA, studyDate);
        dcm.putString(Tag.StudyInstanceUID, VR.UI, studyUid);

        // Series
        dcm.putString(Tag.SeriesDate, VR.DA, seriesDate);
        dcm.putString(Tag.SeriesInstanceUID, VR.UI, seriesUid);
        dcm.putString(Tag.Modality, VR.CS, "CT");

        // Instance
        dcm.putString(Tag.SOPClassUID, VR.UI, "1.2.840.10008.5.1.4.1.1.2");
        dcm.putString(Tag.SOPInstanceUID, VR.UI, sopUid);

        return dcm;
    }
}
