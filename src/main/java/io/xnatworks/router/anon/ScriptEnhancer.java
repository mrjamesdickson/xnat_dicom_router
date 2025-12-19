/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.anon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enhances DicomEdit scripts with dynamic date shifting and UID hashing commands.
 *
 * <p>This class modifies anonymization scripts to:
 * <ul>
 *   <li>Add shiftDateTimeByIncrement[] commands for date fields with a patient-specific offset</li>
 *   <li>Add hashUID[] commands for all UID fields</li>
 * </ul>
 * </p>
 *
 * <p>DicomEdit 6 function reference (https://wiki.xnat.org/xnat-tools/dicomedit-6-language-reference):
 * <ul>
 *   <li>shiftDateTimeByIncrement[(tag), shift, "days"] - Shifts dates by n days. Positive = future, negative = past.</li>
 *   <li>hashUID[(tag)] - Replaces UID with a deterministic hash-based UID (Version 5 UUID, SHA-1).</li>
 * </ul>
 * </p>
 */
public class ScriptEnhancer {
    private static final Logger log = LoggerFactory.getLogger(ScriptEnhancer.class);

    // DICOM date tags that should be shifted
    private static final String[] DATE_TAGS = {
        "(0008,0020)",  // Study Date
        "(0008,0021)",  // Series Date
        "(0008,0022)",  // Acquisition Date
        "(0008,0023)",  // Content Date
        "(0008,002A)",  // Acquisition DateTime
        "(0010,0030)",  // Patient Birth Date
        "(0010,0032)",  // Patient Birth Time
        "(0012,0030)",  // Clinical Trial Time Point Date
        "(0018,9074)",  // Frame Acquisition DateTime
        "(0018,9151)",  // Frame Reference DateTime
        "(0020,3401)",  // Modifying Device ID (often contains dates)
        "(0032,1000)",  // Scheduled Study Start Date
        "(0032,1010)",  // Scheduled Study Stop Date
        "(0032,1040)",  // Study Arrival Date
        "(0032,1050)",  // Study Completion Date
        "(0038,001A)",  // Scheduled Admission Date
        "(0038,001C)",  // Scheduled Discharge Date
        "(0038,0020)",  // Admitting Date
        "(0038,0030)",  // Discharge Date
        "(0040,0002)",  // Scheduled Procedure Step Start Date
        "(0040,0004)",  // Scheduled Procedure Step End Date
        "(0040,0244)",  // Performed Procedure Step Start Date
        "(0040,0250)",  // Performed Procedure Step End Date
        "(0040,A121)",  // Date (Content Sequence)
        "(0040,A122)",  // Time (Content Sequence)
    };

    // DICOM time tags that should be shifted (associated with date tags)
    private static final String[] TIME_TAGS = {
        "(0008,0030)",  // Study Time
        "(0008,0031)",  // Series Time
        "(0008,0032)",  // Acquisition Time
        "(0008,0033)",  // Content Time
    };

    // DICOM UID tags that should be hashed
    private static final String[] UID_TAGS = {
        "(0020,000D)",  // Study Instance UID
        "(0020,000E)",  // Series Instance UID
        "(0008,0018)",  // SOP Instance UID
        "(0020,0052)",  // Frame of Reference UID
        "(0088,0140)",  // Storage Media File-set UID
        "(3006,0024)",  // Referenced Frame of Reference UID
        "(0008,1115)",  // Referenced Series Sequence (contains UIDs)
        "(0008,1155)",  // Referenced SOP Instance UID
        "(0020,0200)",  // Synchronization Frame of Reference UID
    };

    /**
     * Enhance a script with date shifting for all date fields.
     * Uses DicomEdit 6's shiftDateTimeByIncrement function.
     *
     * @param script The original DicomEdit script
     * @param dateShiftDays Number of days to shift (negative = earlier, positive = later)
     * @return Enhanced script with shiftDateTimeByIncrement[] commands
     */
    public static String enhanceWithDateShift(String script, int dateShiftDays) {
        if (dateShiftDays == 0) {
            log.debug("Date shift is 0 days, no modification needed");
            return script;
        }

        StringBuilder enhanced = new StringBuilder();
        enhanced.append(script);

        // Add date shift commands using DicomEdit 6 syntax
        // NOTE: When using (tag) := shiftDateTimeByIncrement[(tag), ...], the shift is applied twice
        // because the RHS reads the tag (applying shift) and then assigns back (applying shift again).
        // Workaround: divide the requested shift by 2 so the net effect is correct.
        int adjustedShift = dateShiftDays / 2;
        int remainder = dateShiftDays % 2;
        if (remainder != 0) {
            log.warn("Date shift {} is odd, will be rounded to {} days (effective: {} days)",
                    dateShiftDays, adjustedShift, adjustedShift * 2);
        }

        enhanced.append("\n\n// Date Shifting - Patient-specific offset: ").append(dateShiftDays).append(" days\n");
        enhanced.append("// Using DicomEdit 6 shiftDateTimeByIncrement function\n");
        enhanced.append("// Note: Script uses ").append(adjustedShift).append(" to achieve ").append(adjustedShift * 2).append(" day shift (DicomEdit applies twice)\n");

        List<String> existingTags = extractExistingTags(script);

        for (String tag : DATE_TAGS) {
            // Don't add if script already sets this tag to empty or specific value
            if (!tagIsModified(script, tag) || tagIsClearedOnly(script, tag)) {
                // Only add shift if the tag exists in DICOM and isn't being cleared
                if (!tagIsCleared(script, tag)) {
                    // DicomEdit 6 syntax: shiftDateTimeByIncrement[(tag), "shift", "days"]
                    enhanced.append(tag).append(" := shiftDateTimeByIncrement[").append(tag)
                            .append(", \"").append(adjustedShift).append("\", \"days\"]")
                            .append("  // Shift by ").append(dateShiftDays).append(" days\n");
                }
            }
        }

        log.debug("Enhanced script with date shift of {} days", dateShiftDays);
        return enhanced.toString();
    }

    /**
     * Enhance a script with hashUID[] for all UID fields.
     * Note: Many scripts already include hashUID for UIDs. This adds it for any that are missing.
     *
     * @param script The original DicomEdit script
     * @return Enhanced script with hashUID[] commands for all UIDs
     */
    public static String enhanceWithUidHashing(String script) {
        StringBuilder enhanced = new StringBuilder();
        enhanced.append(script);

        // Check which UIDs are already being hashed
        boolean anyAdded = false;
        enhanced.append("\n\n// UID Hashing - All UIDs will be hashed consistently\n");

        for (String tag : UID_TAGS) {
            // Only add if not already present in script
            if (!tagHasHashUid(script, tag) && !tagIsModified(script, tag)) {
                enhanced.append(tag).append(" := hashUID[").append(tag).append("]\n");
                anyAdded = true;
            }
        }

        if (!anyAdded) {
            log.debug("All UIDs already have hashUID in script, no modification needed");
            return script;
        }

        log.debug("Enhanced script with hashUID for missing UID tags");
        return enhanced.toString();
    }

    /**
     * Enhance a script with both date shifting and UID hashing.
     *
     * @param script The original DicomEdit script
     * @param dateShiftDays Number of days to shift (0 to disable)
     * @param hashUids Whether to add hashUID for all UIDs
     * @return Enhanced script
     */
    public static String enhance(String script, int dateShiftDays, boolean hashUids) {
        String result = script;

        if (dateShiftDays != 0) {
            result = enhanceWithDateShift(result, dateShiftDays);
        }

        if (hashUids) {
            result = enhanceWithUidHashing(result);
        }

        return result;
    }

    /**
     * Extract tags that are already modified in the script.
     */
    private static List<String> extractExistingTags(String script) {
        List<String> tags = new ArrayList<>();
        // Pattern to match tag at start of line: (gggg,eeee)
        Pattern pattern = Pattern.compile("^\\s*\\(([0-9A-Fa-f]{4}),([0-9A-Fa-f]{4})\\)", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(script);
        while (matcher.find()) {
            tags.add("(" + matcher.group(1) + "," + matcher.group(2) + ")");
        }
        return tags;
    }

    /**
     * Check if a tag is already modified in the script.
     */
    private static boolean tagIsModified(String script, String tag) {
        // Normalize tag format for comparison
        String normalizedTag = tag.toLowerCase();
        String scriptLower = script.toLowerCase();

        // Check if tag appears at the start of an assignment line
        Pattern pattern = Pattern.compile("^\\s*" + Pattern.quote(normalizedTag) + "\\s*:=", Pattern.MULTILINE);
        return pattern.matcher(scriptLower).find();
    }

    /**
     * Check if a tag is cleared (set to empty string) in the script.
     */
    private static boolean tagIsCleared(String script, String tag) {
        String normalizedTag = tag.toLowerCase();
        String scriptLower = script.toLowerCase();

        // Check for patterns like: (tag) := ""
        Pattern pattern = Pattern.compile("^\\s*" + Pattern.quote(normalizedTag) + "\\s*:=\\s*\"\"", Pattern.MULTILINE);
        return pattern.matcher(scriptLower).find();
    }

    /**
     * Check if a tag is only cleared (not transformed) in the script.
     */
    private static boolean tagIsClearedOnly(String script, String tag) {
        if (!tagIsModified(script, tag)) {
            return false;
        }
        return tagIsCleared(script, tag);
    }

    /**
     * Check if a tag already has hashUID applied.
     */
    private static boolean tagHasHashUid(String script, String tag) {
        String normalizedTag = tag.toLowerCase();
        String scriptLower = script.toLowerCase();

        // Check for patterns like: (tag) := hashUID[(tag)]
        Pattern pattern = Pattern.compile(Pattern.quote(normalizedTag) + "\\s*:=\\s*hashuid\\s*\\[", Pattern.MULTILINE);
        return pattern.matcher(scriptLower).find();
    }

    /**
     * Get the list of date tags that would be shifted.
     * Useful for generating audit reports.
     */
    public static String[] getDateTags() {
        return DATE_TAGS.clone();
    }

    /**
     * Get the list of UID tags that would be hashed.
     * Useful for generating audit reports.
     */
    public static String[] getUidTags() {
        return UID_TAGS.clone();
    }
}
