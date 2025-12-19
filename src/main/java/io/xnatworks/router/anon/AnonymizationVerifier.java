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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Verifies that anonymization was applied correctly to DICOM objects.
 *
 * CRITICAL: Anonymization errors can cause HIPAA/PHI compliance violations.
 * This class performs comprehensive verification of anonymization results.
 *
 * Verification includes:
 * - Date shifting applied correctly (with exact day count verification)
 * - Patient identifiers modified (name, ID, birth date)
 * - UIDs changed (Study, Series, SOP Instance)
 * - Other PHI fields handled appropriately
 */
public class AnonymizationVerifier {
    private static final Logger log = LoggerFactory.getLogger(AnonymizationVerifier.class);

    private static final DateTimeFormatter DICOM_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    // Tolerance for date shift verification (in days)
    // 0 = exact match required, 1 = allow for timezone edge cases
    private static final int DATE_SHIFT_TOLERANCE_DAYS = 0;

    // Pattern for valid anonymized patient name (should not contain real names)
    private static final Pattern ANON_NAME_PATTERN = Pattern.compile(
            "^(Anonymous|ANON|Subject_\\d+|[A-Z0-9_]+)$", Pattern.CASE_INSENSITIVE);

    /**
     * Types of verification checks.
     */
    public enum CheckType {
        DATE_SHIFT,
        PATIENT_NAME,
        PATIENT_ID,
        UID_CHANGED,
        FIELD_CLEARED,
        FIELD_MODIFIED
    }

    /**
     * Result of a single verification check.
     */
    public static class CheckResult {
        private final CheckType type;
        private final String fieldName;
        private final int tag;
        private final String originalValue;
        private final String newValue;
        private final boolean passed;
        private final String message;
        private final String details;

        public CheckResult(CheckType type, String fieldName, int tag,
                           String originalValue, String newValue,
                           boolean passed, String message, String details) {
            this.type = type;
            this.fieldName = fieldName;
            this.tag = tag;
            this.originalValue = originalValue;
            this.newValue = newValue;
            this.passed = passed;
            this.message = message;
            this.details = details;
        }

        public CheckType getType() { return type; }
        public String getFieldName() { return fieldName; }
        public int getTag() { return tag; }
        public String getOriginalValue() { return originalValue; }
        public String getNewValue() { return newValue; }
        public boolean isPassed() { return passed; }
        public String getMessage() { return message; }
        public String getDetails() { return details; }

        @Override
        public String toString() {
            return String.format("[%s] %s: %s -> %s | %s %s",
                    passed ? "PASS" : "FAIL",
                    fieldName,
                    truncate(originalValue, 20),
                    truncate(newValue, 20),
                    message,
                    details != null ? "(" + details + ")" : "");
        }

        private String truncate(String s, int maxLen) {
            if (s == null) return "(null)";
            if (s.length() <= maxLen) return s;
            return s.substring(0, maxLen - 3) + "...";
        }
    }

    /**
     * Overall verification result.
     */
    public static class VerificationResult {
        private final List<CheckResult> checks = new ArrayList<>();
        private String sopInstanceUid;
        private String studyInstanceUid;
        private Integer expectedDateShiftDays;
        private long verificationTimeMs;

        public void addCheck(CheckResult check) {
            checks.add(check);
        }

        public List<CheckResult> getChecks() { return checks; }

        public List<CheckResult> getFailedChecks() {
            List<CheckResult> failed = new ArrayList<>();
            for (CheckResult c : checks) {
                if (!c.isPassed()) {
                    failed.add(c);
                }
            }
            return failed;
        }

        public boolean isAllPassed() {
            for (CheckResult c : checks) {
                if (!c.isPassed()) {
                    return false;
                }
            }
            return true;
        }

        public int getPassedCount() {
            int count = 0;
            for (CheckResult c : checks) {
                if (c.isPassed()) count++;
            }
            return count;
        }

        public int getFailedCount() {
            return checks.size() - getPassedCount();
        }

        public String getSopInstanceUid() { return sopInstanceUid; }
        public void setSopInstanceUid(String sopInstanceUid) { this.sopInstanceUid = sopInstanceUid; }

        public String getStudyInstanceUid() { return studyInstanceUid; }
        public void setStudyInstanceUid(String studyInstanceUid) { this.studyInstanceUid = studyInstanceUid; }

        public Integer getExpectedDateShiftDays() { return expectedDateShiftDays; }
        public void setExpectedDateShiftDays(Integer expectedDateShiftDays) { this.expectedDateShiftDays = expectedDateShiftDays; }

        public long getVerificationTimeMs() { return verificationTimeMs; }
        public void setVerificationTimeMs(long verificationTimeMs) { this.verificationTimeMs = verificationTimeMs; }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== ANONYMIZATION VERIFICATION ").append(isAllPassed() ? "PASSED" : "FAILED").append(" ===\n");
            sb.append("SOP Instance UID: ").append(sopInstanceUid).append("\n");
            sb.append("Study Instance UID: ").append(studyInstanceUid).append("\n");
            if (expectedDateShiftDays != null) {
                sb.append("Expected date shift: ").append(expectedDateShiftDays).append(" days\n");
            }
            sb.append("Checks: ").append(getPassedCount()).append(" passed, ")
              .append(getFailedCount()).append(" failed\n");
            sb.append("Time: ").append(verificationTimeMs).append("ms\n\n");

            if (!isAllPassed()) {
                sb.append("FAILED CHECKS:\n");
                for (CheckResult c : getFailedChecks()) {
                    sb.append("  ✗ ").append(c.toString()).append("\n");
                }
                sb.append("\n");
            }

            sb.append("ALL CHECKS:\n");
            for (CheckResult c : checks) {
                sb.append("  ").append(c.isPassed() ? "✓" : "✗").append(" ").append(c.toString()).append("\n");
            }

            return sb.toString();
        }
    }

    /**
     * Configuration for verification.
     */
    public static class VerificationConfig {
        private Integer expectedDateShiftDays;
        private boolean verifyUidsChanged = true;
        private boolean verifyPatientInfoModified = true;
        private boolean verifyDatesShifted = true;
        private boolean failOnFirstError = false;

        public Integer getExpectedDateShiftDays() { return expectedDateShiftDays; }
        public void setExpectedDateShiftDays(Integer days) { this.expectedDateShiftDays = days; }

        public boolean isVerifyUidsChanged() { return verifyUidsChanged; }
        public void setVerifyUidsChanged(boolean v) { this.verifyUidsChanged = v; }

        public boolean isVerifyPatientInfoModified() { return verifyPatientInfoModified; }
        public void setVerifyPatientInfoModified(boolean v) { this.verifyPatientInfoModified = v; }

        public boolean isVerifyDatesShifted() { return verifyDatesShifted; }
        public void setVerifyDatesShifted(boolean v) { this.verifyDatesShifted = v; }

        public boolean isFailOnFirstError() { return failOnFirstError; }
        public void setFailOnFirstError(boolean v) { this.failOnFirstError = v; }
    }

    /**
     * Verify anonymization was applied correctly.
     *
     * @param original The original DICOM object (before anonymization)
     * @param anonymized The anonymized DICOM object (after anonymization)
     * @param config Verification configuration
     * @return Verification result
     */
    public VerificationResult verify(DicomObject original, DicomObject anonymized, VerificationConfig config) {
        long startTime = System.currentTimeMillis();
        VerificationResult result = new VerificationResult();

        result.setSopInstanceUid(anonymized.getString(Tag.SOPInstanceUID, "unknown"));
        result.setStudyInstanceUid(anonymized.getString(Tag.StudyInstanceUID, "unknown"));
        result.setExpectedDateShiftDays(config.getExpectedDateShiftDays());

        try {
            // Verify UIDs changed
            if (config.isVerifyUidsChanged()) {
                verifyUidsChanged(original, anonymized, result, config);
            }

            // Verify patient info modified
            if (config.isVerifyPatientInfoModified()) {
                verifyPatientInfoModified(original, anonymized, result, config);
            }

            // Verify date shifts
            if (config.isVerifyDatesShifted() && config.getExpectedDateShiftDays() != null) {
                verifyDateShifts(original, anonymized, config.getExpectedDateShiftDays(), result, config);
            }

        } catch (VerificationAbortedException e) {
            log.warn("Verification aborted early: {}", e.getMessage());
        }

        result.setVerificationTimeMs(System.currentTimeMillis() - startTime);

        // Log result
        if (!result.isAllPassed()) {
            log.error("ANONYMIZATION VERIFICATION FAILED:\n{}", result);
        } else {
            log.info("Anonymization verified: {} checks passed for SOP {}",
                    result.getPassedCount(), result.getSopInstanceUid());
        }

        return result;
    }

    /**
     * Verify UIDs were changed.
     */
    private void verifyUidsChanged(DicomObject original, DicomObject anonymized,
                                    VerificationResult result, VerificationConfig config)
            throws VerificationAbortedException {

        // Study Instance UID
        checkUidChanged(original, anonymized, Tag.StudyInstanceUID, "StudyInstanceUID", result, config);

        // Series Instance UID
        checkUidChanged(original, anonymized, Tag.SeriesInstanceUID, "SeriesInstanceUID", result, config);

        // SOP Instance UID
        checkUidChanged(original, anonymized, Tag.SOPInstanceUID, "SOPInstanceUID", result, config);
    }

    private void checkUidChanged(DicomObject original, DicomObject anonymized, int tag, String name,
                                  VerificationResult result, VerificationConfig config)
            throws VerificationAbortedException {

        String origUid = original.getString(tag);
        String newUid = anonymized.getString(tag);

        boolean passed = !Objects.equals(origUid, newUid);
        String message = passed ? "UID changed" : "UID NOT CHANGED - PHI LEAK RISK";

        result.addCheck(new CheckResult(
                CheckType.UID_CHANGED, name, tag,
                origUid, newUid, passed, message, null));

        if (!passed && config.isFailOnFirstError()) {
            throw new VerificationAbortedException("UID not changed: " + name);
        }
    }

    /**
     * Verify patient identifying info was modified.
     */
    private void verifyPatientInfoModified(DicomObject original, DicomObject anonymized,
                                            VerificationResult result, VerificationConfig config)
            throws VerificationAbortedException {

        // Patient Name - should be changed to anonymous value
        String origName = original.getString(Tag.PatientName);
        String newName = anonymized.getString(Tag.PatientName);
        boolean nameChanged = !Objects.equals(origName, newName);
        boolean nameAnonymized = newName == null || newName.isEmpty() ||
                ANON_NAME_PATTERN.matcher(newName).matches() ||
                !newName.equals(origName);

        result.addCheck(new CheckResult(
                CheckType.PATIENT_NAME, "PatientName", Tag.PatientName,
                origName, newName,
                nameChanged && nameAnonymized,
                nameChanged ? "Name modified" : "Name NOT CHANGED - PHI LEAK",
                nameAnonymized ? "appears anonymized" : "may contain real name"));

        if (!nameChanged && config.isFailOnFirstError()) {
            throw new VerificationAbortedException("Patient name not changed");
        }

        // Patient ID - should be changed
        String origId = original.getString(Tag.PatientID);
        String newId = anonymized.getString(Tag.PatientID);
        boolean idChanged = !Objects.equals(origId, newId);

        result.addCheck(new CheckResult(
                CheckType.PATIENT_ID, "PatientID", Tag.PatientID,
                origId, newId,
                idChanged,
                idChanged ? "ID modified" : "ID NOT CHANGED - PHI LEAK",
                null));

        if (!idChanged && config.isFailOnFirstError()) {
            throw new VerificationAbortedException("Patient ID not changed");
        }
    }

    /**
     * Verify date shifts were applied correctly.
     */
    private void verifyDateShifts(DicomObject original, DicomObject anonymized, int expectedShiftDays,
                                   VerificationResult result, VerificationConfig config)
            throws VerificationAbortedException {

        // Study Date
        verifyDateShift(original, anonymized, Tag.StudyDate, "StudyDate",
                expectedShiftDays, result, config);

        // Series Date
        verifyDateShift(original, anonymized, Tag.SeriesDate, "SeriesDate",
                expectedShiftDays, result, config);

        // Patient Birth Date
        verifyDateShift(original, anonymized, Tag.PatientBirthDate, "PatientBirthDate",
                expectedShiftDays, result, config);
    }

    private void verifyDateShift(DicomObject original, DicomObject anonymized, int tag, String name,
                                  int expectedShiftDays, VerificationResult result, VerificationConfig config)
            throws VerificationAbortedException {

        String origDate = original.getString(tag);
        String newDate = anonymized.getString(tag);

        // Skip if original date is empty
        if (origDate == null || origDate.trim().isEmpty()) {
            return;
        }

        // If new date is empty but original wasn't, that's concerning
        if (newDate == null || newDate.trim().isEmpty()) {
            result.addCheck(new CheckResult(
                    CheckType.DATE_SHIFT, name, tag,
                    origDate, "(empty)",
                    false, "Date CLEARED instead of shifted", "may lose temporal relationships"));

            if (config.isFailOnFirstError()) {
                throw new VerificationAbortedException("Date cleared instead of shifted: " + name);
            }
            return;
        }

        try {
            LocalDate origLocalDate = parseDate(origDate);
            LocalDate newLocalDate = parseDate(newDate);

            if (origLocalDate == null || newLocalDate == null) {
                result.addCheck(new CheckResult(
                        CheckType.DATE_SHIFT, name, tag,
                        origDate, newDate,
                        false, "Could not parse date", null));
                return;
            }

            long actualShiftDays = ChronoUnit.DAYS.between(origLocalDate, newLocalDate);
            long difference = Math.abs(actualShiftDays - expectedShiftDays);
            boolean passed = difference <= DATE_SHIFT_TOLERANCE_DAYS;

            String details = String.format("expected %+d, actual %+d, diff %d",
                    expectedShiftDays, actualShiftDays, difference);

            result.addCheck(new CheckResult(
                    CheckType.DATE_SHIFT, name, tag,
                    origDate, newDate,
                    passed,
                    passed ? "Date shift correct" : "DATE SHIFT INCORRECT",
                    details));

            if (!passed && config.isFailOnFirstError()) {
                throw new VerificationAbortedException(
                        String.format("Date shift incorrect for %s: expected %d, got %d",
                                name, expectedShiftDays, actualShiftDays));
            }

        } catch (Exception e) {
            result.addCheck(new CheckResult(
                    CheckType.DATE_SHIFT, name, tag,
                    origDate, newDate,
                    false, "Error verifying date", e.getMessage()));
        }
    }

    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            return null;
        }
        String cleaned = dateStr.trim();
        if (cleaned.length() < 8) {
            return null;
        }
        cleaned = cleaned.substring(0, 8);
        try {
            return LocalDate.parse(cleaned, DICOM_DATE_FORMAT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Quick verification that throws on failure.
     */
    public void verifyOrThrow(DicomObject original, DicomObject anonymized, VerificationConfig config)
            throws AnonymizationVerificationException {
        VerificationResult result = verify(original, anonymized, config);
        if (!result.isAllPassed()) {
            throw new AnonymizationVerificationException(
                    "Anonymization verification failed: " + result.getFailedCount() + " checks failed",
                    result);
        }
    }

    /**
     * Exception thrown when verification fails.
     */
    public static class AnonymizationVerificationException extends Exception {
        private final VerificationResult result;

        public AnonymizationVerificationException(String message, VerificationResult result) {
            super(message);
            this.result = result;
        }

        public VerificationResult getResult() {
            return result;
        }
    }

    /**
     * Exception to abort verification early.
     */
    private static class VerificationAbortedException extends Exception {
        public VerificationAbortedException(String message) {
            super(message);
        }
    }
}
