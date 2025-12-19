/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */
package io.xnatworks.router.anon;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ScriptEnhancer.
 * Tests DicomEdit 6 script enhancement with date shifting and UID hashing.
 */
@DisplayName("ScriptEnhancer Tests")
class ScriptEnhancerTest {

    private static final String SAMPLE_SCRIPT = """
        version "6.6"
        // Basic anonymization script
        (0010,0010) := "ANONYMOUS"
        (0010,0020) := "SUBJECT001"
        """;

    private static final String SCRIPT_WITH_DATE_CLEARED = """
        version "6.6"
        (0010,0010) := "ANONYMOUS"
        (0008,0020) := ""
        """;

    private static final String SCRIPT_WITH_HASHUID = """
        version "6.6"
        (0010,0010) := "ANONYMOUS"
        (0020,000D) := hashUID[(0020,000D)]
        """;

    @Nested
    @DisplayName("Date Shifting Tests")
    class DateShiftingTests {

        @Test
        @DisplayName("Should add shiftDateTimeByIncrement commands for date fields")
        void shouldAddDateShiftCommands() {
            String enhanced = ScriptEnhancer.enhanceWithDateShift(SAMPLE_SCRIPT, -365);

            // Should contain the DicomEdit 6 shiftDateTimeByIncrement syntax
            assertTrue(enhanced.contains("shiftDateTimeByIncrement["));
            assertTrue(enhanced.contains("\"-365\""));
            assertTrue(enhanced.contains("\"days\""));

            // Should include Study Date
            assertTrue(enhanced.contains("(0008,0020)"));

            // Should include Patient Birth Date
            assertTrue(enhanced.contains("(0010,0030)"));
        }

        @Test
        @DisplayName("Should not modify script when date shift is 0")
        void shouldNotModifyWhenDateShiftIsZero() {
            String enhanced = ScriptEnhancer.enhanceWithDateShift(SAMPLE_SCRIPT, 0);

            assertEquals(SAMPLE_SCRIPT, enhanced);
        }

        @Test
        @DisplayName("Should handle positive date shift (future)")
        void shouldHandlePositiveDateShift() {
            String enhanced = ScriptEnhancer.enhanceWithDateShift(SAMPLE_SCRIPT, 30);

            assertTrue(enhanced.contains("\"30\""));
            assertTrue(enhanced.contains("\"days\""));
        }

        @Test
        @DisplayName("Should handle negative date shift (past)")
        void shouldHandleNegativeDateShift() {
            String enhanced = ScriptEnhancer.enhanceWithDateShift(SAMPLE_SCRIPT, -730);

            assertTrue(enhanced.contains("\"-730\""));
            assertTrue(enhanced.contains("\"days\""));
        }

        @Test
        @DisplayName("Should not add date shift for cleared tags")
        void shouldNotAddDateShiftForClearedTags() {
            String enhanced = ScriptEnhancer.enhanceWithDateShift(SCRIPT_WITH_DATE_CLEARED, -365);

            // Study Date (0008,0020) is cleared in the script, so should not add shift for it
            // Check that shiftDateTimeByIncrement is NOT added for this specific tag
            String studyDatePattern = "(0008,0020) := shiftDateTimeByIncrement";
            assertFalse(enhanced.contains(studyDatePattern));

            // But other date tags should still be shifted
            assertTrue(enhanced.contains("(0010,0030)")); // Patient Birth Date
        }

        @Test
        @DisplayName("Should use correct DicomEdit 6 syntax")
        void shouldUseCorrectDicomEdit6Syntax() {
            String enhanced = ScriptEnhancer.enhanceWithDateShift(SAMPLE_SCRIPT, -100);

            // Verify the exact syntax: tag := shiftDateTimeByIncrement[tag, "shift", "days"]
            assertTrue(enhanced.contains("shiftDateTimeByIncrement[(0008,0020), \"-100\", \"days\"]") ||
                       enhanced.contains("shiftDateTimeByIncrement[(0010,0030), \"-100\", \"days\"]"));
        }
    }

    @Nested
    @DisplayName("UID Hashing Tests")
    class UidHashingTests {

        @Test
        @DisplayName("Should add hashUID commands for UID fields")
        void shouldAddHashUidCommands() {
            String enhanced = ScriptEnhancer.enhanceWithUidHashing(SAMPLE_SCRIPT);

            // Should contain hashUID syntax
            assertTrue(enhanced.contains("hashUID["));

            // Should include Study Instance UID
            assertTrue(enhanced.contains("(0020,000D)"));

            // Should include Series Instance UID
            assertTrue(enhanced.contains("(0020,000E)"));

            // Should include SOP Instance UID
            assertTrue(enhanced.contains("(0008,0018)"));
        }

        @Test
        @DisplayName("Should not add duplicate hashUID for already hashed tags")
        void shouldNotAddDuplicateHashUid() {
            String enhanced = ScriptEnhancer.enhanceWithUidHashing(SCRIPT_WITH_HASHUID);

            // Count occurrences of Study Instance UID with hashUID
            int count = countOccurrences(enhanced, "(0020,000D) := hashUID");
            assertEquals(1, count, "Should have exactly one hashUID for Study Instance UID");
        }

        @Test
        @DisplayName("Should return original script if all UIDs already hashed")
        void shouldReturnOriginalWhenAllUidsHashed() {
            String scriptWithAllUids = """
                version "6.6"
                (0020,000D) := hashUID[(0020,000D)]
                (0020,000E) := hashUID[(0020,000E)]
                (0008,0018) := hashUID[(0008,0018)]
                (0020,0052) := hashUID[(0020,0052)]
                (0088,0140) := hashUID[(0088,0140)]
                (3006,0024) := hashUID[(3006,0024)]
                (0008,1115) := hashUID[(0008,1115)]
                (0008,1155) := hashUID[(0008,1155)]
                (0020,0200) := hashUID[(0020,0200)]
                """;

            String enhanced = ScriptEnhancer.enhanceWithUidHashing(scriptWithAllUids);

            // Should not add any new sections
            assertEquals(scriptWithAllUids, enhanced);
        }
    }

    @Nested
    @DisplayName("Combined Enhancement Tests")
    class CombinedEnhancementTests {

        @Test
        @DisplayName("Should add both date shift and UID hashing when requested")
        void shouldAddBothEnhancements() {
            String enhanced = ScriptEnhancer.enhance(SAMPLE_SCRIPT, -365, true);

            // Should contain date shift commands
            assertTrue(enhanced.contains("shiftDateTimeByIncrement["));
            assertTrue(enhanced.contains("\"-365\""));

            // Should contain UID hash commands
            assertTrue(enhanced.contains("hashUID["));
        }

        @Test
        @DisplayName("Should add only date shift when hashUids is false")
        void shouldAddOnlyDateShift() {
            String enhanced = ScriptEnhancer.enhance(SAMPLE_SCRIPT, -365, false);

            // Should contain date shift commands
            assertTrue(enhanced.contains("shiftDateTimeByIncrement["));

            // Should NOT contain UID Hashing section header
            assertFalse(enhanced.contains("// UID Hashing"));
        }

        @Test
        @DisplayName("Should add only UID hashing when date shift is 0")
        void shouldAddOnlyUidHashing() {
            String enhanced = ScriptEnhancer.enhance(SAMPLE_SCRIPT, 0, true);

            // Should NOT contain date shift commands
            assertFalse(enhanced.contains("shiftDateTimeByIncrement["));

            // Should contain UID hash commands
            assertTrue(enhanced.contains("hashUID["));
        }

        @Test
        @DisplayName("Should return original script when no enhancements requested")
        void shouldReturnOriginalWhenNoEnhancements() {
            String enhanced = ScriptEnhancer.enhance(SAMPLE_SCRIPT, 0, false);

            assertEquals(SAMPLE_SCRIPT, enhanced);
        }
    }

    @Nested
    @DisplayName("Tag Listing Tests")
    class TagListingTests {

        @Test
        @DisplayName("Should return correct date tags list")
        void shouldReturnCorrectDateTags() {
            String[] dateTags = ScriptEnhancer.getDateTags();

            assertNotNull(dateTags);
            assertTrue(dateTags.length > 0);

            // Check for essential date tags
            boolean hasStudyDate = false;
            boolean hasPatientBirthDate = false;
            for (String tag : dateTags) {
                if ("(0008,0020)".equals(tag)) hasStudyDate = true;
                if ("(0010,0030)".equals(tag)) hasPatientBirthDate = true;
            }
            assertTrue(hasStudyDate, "Should include Study Date");
            assertTrue(hasPatientBirthDate, "Should include Patient Birth Date");
        }

        @Test
        @DisplayName("Should return correct UID tags list")
        void shouldReturnCorrectUidTags() {
            String[] uidTags = ScriptEnhancer.getUidTags();

            assertNotNull(uidTags);
            assertTrue(uidTags.length > 0);

            // Check for essential UID tags
            boolean hasStudyInstanceUid = false;
            boolean hasSeriesInstanceUid = false;
            boolean hasSopInstanceUid = false;
            for (String tag : uidTags) {
                if ("(0020,000D)".equals(tag)) hasStudyInstanceUid = true;
                if ("(0020,000E)".equals(tag)) hasSeriesInstanceUid = true;
                if ("(0008,0018)".equals(tag)) hasSopInstanceUid = true;
            }
            assertTrue(hasStudyInstanceUid, "Should include Study Instance UID");
            assertTrue(hasSeriesInstanceUid, "Should include Series Instance UID");
            assertTrue(hasSopInstanceUid, "Should include SOP Instance UID");
        }

        @Test
        @DisplayName("Should return defensive copies of tag arrays")
        void shouldReturnDefensiveCopies() {
            String[] dateTags1 = ScriptEnhancer.getDateTags();
            String[] dateTags2 = ScriptEnhancer.getDateTags();

            assertNotSame(dateTags1, dateTags2, "Should return new array each time");

            // Modify the returned array
            dateTags1[0] = "MODIFIED";

            // Original should not be affected
            String[] dateTags3 = ScriptEnhancer.getDateTags();
            assertNotEquals("MODIFIED", dateTags3[0]);
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("Should handle empty script")
        void shouldHandleEmptyScript() {
            String enhanced = ScriptEnhancer.enhance("", -365, true);

            assertTrue(enhanced.contains("shiftDateTimeByIncrement["));
            assertTrue(enhanced.contains("hashUID["));
        }

        @Test
        @DisplayName("Should handle script with comments")
        void shouldHandleScriptWithComments() {
            String scriptWithComments = """
                version "6.6"
                // This is a comment
                (0010,0010) := "ANONYMOUS"
                // Another comment about dates
                // (0008,0020) := "some date"  // This line is commented out
                """;

            String enhanced = ScriptEnhancer.enhanceWithDateShift(scriptWithComments, -30);

            // Should still add date shift commands
            assertTrue(enhanced.contains("shiftDateTimeByIncrement["));
        }

        @Test
        @DisplayName("Should handle case-insensitive tag matching")
        void shouldHandleCaseInsensitiveTagMatching() {
            String scriptWithUppercaseTags = """
                version "6.6"
                (0008,0020) := ""
                (0020,000D) := hashUID[(0020,000D)]
                """;

            String enhanced = ScriptEnhancer.enhanceWithUidHashing(scriptWithUppercaseTags);

            // Should not add duplicate hashUID for Study Instance UID
            int count = countOccurrences(enhanced, "hashUID[(0020,000D)]");
            assertEquals(1, count);
        }
    }

    /**
     * Helper method to count occurrences of a substring.
     */
    private int countOccurrences(String text, String substring) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        return count;
    }
}
