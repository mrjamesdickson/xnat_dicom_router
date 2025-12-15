/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */
package io.xnatworks.router.ocr;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Pattern matcher for identifying potential Protected Health Information (PHI)
 * in text extracted from DICOM images via OCR.
 *
 * Matches common PHI patterns including:
 * - Patient names (capitalized multi-word)
 * - Dates in various formats
 * - Medical record numbers (MRN)
 * - Social Security Numbers
 * - Phone numbers
 * - Accession numbers
 * - Institution names
 * - Patient identifiers
 * - Date of birth
 * - Age
 * - Sex/Gender
 */
public class PhiPatternMatcher {

    private final List<Pattern> phiPatterns;

    public PhiPatternMatcher() {
        phiPatterns = new ArrayList<>();

        // Patient names - capitalized multi-word patterns
        phiPatterns.add(Pattern.compile("^[A-Z][a-z]+(?:\\s+[A-Z][a-z]+)+$"));

        // Dates in various formats: MM/DD/YYYY, DD-MM-YYYY, YYYY-MM-DD, etc.
        phiPatterns.add(Pattern.compile("\\b\\d{1,2}[/\\-.]\\d{1,2}[/\\-.]\\d{2,4}\\b"));
        phiPatterns.add(Pattern.compile("\\b\\d{4}[/\\-.]\\d{1,2}[/\\-.]\\d{1,2}\\b"));

        // Medical record numbers (MRN)
        phiPatterns.add(Pattern.compile("\\b(?:MRN|MR#?|ID|Patient\\s*ID)[:\\s]*\\d{4,12}\\b", Pattern.CASE_INSENSITIVE));

        // Social Security Numbers (with or without dashes)
        phiPatterns.add(Pattern.compile("\\b\\d{3}[-\\s]?\\d{2}[-\\s]?\\d{4}\\b"));

        // Phone numbers (US format)
        phiPatterns.add(Pattern.compile("\\b(?:\\+?1[-.]?\\s?)?\\(?\\d{3}\\)?[-.]?\\s?\\d{3}[-.]?\\s?\\d{4}\\b"));

        // Accession numbers
        phiPatterns.add(Pattern.compile("\\b(?:ACC|Accession)[:\\s#]*[A-Z0-9]{6,12}\\b", Pattern.CASE_INSENSITIVE));

        // Institution/Hospital names
        phiPatterns.add(Pattern.compile("\\b(?:Hospital|Medical\\s*Center|Clinic|Institute|University|Health\\s*System)\\b", Pattern.CASE_INSENSITIVE));

        // Patient label patterns
        phiPatterns.add(Pattern.compile("\\b(?:Patient|PT|Name)[:\\s]+[A-Za-z\\s,]+\\b", Pattern.CASE_INSENSITIVE));

        // Date of Birth
        phiPatterns.add(Pattern.compile("\\b(?:DOB|Date\\s*of\\s*Birth|Birth\\s*Date)[:\\s]*[\\d/\\-.]+\\b", Pattern.CASE_INSENSITIVE));

        // Age patterns
        phiPatterns.add(Pattern.compile("\\b(?:Age)[:\\s]*\\d{1,3}(?:\\s*(?:Y|YR|YRS|Years?|M|MO|D|Days?))?\\b", Pattern.CASE_INSENSITIVE));

        // Sex/Gender
        phiPatterns.add(Pattern.compile("\\b(?:Sex|Gender)[:\\s]*(?:M|F|Male|Female|Other)\\b", Pattern.CASE_INSENSITIVE));

        // Weight/Height (may appear on images)
        phiPatterns.add(Pattern.compile("\\b(?:Weight|WT)[:\\s]*\\d+(?:\\.\\d+)?\\s*(?:kg|lb|lbs)?\\b", Pattern.CASE_INSENSITIVE));
        phiPatterns.add(Pattern.compile("\\b(?:Height|HT)[:\\s]*\\d+(?:\\.\\d+)?\\s*(?:cm|in|ft)?\\b", Pattern.CASE_INSENSITIVE));

        // Referring physician
        phiPatterns.add(Pattern.compile("\\b(?:Referring|Ref\\.?|Dr\\.?|Doctor)[:\\s]*[A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*\\b", Pattern.CASE_INSENSITIVE));

        // Study/Exam descriptions that may contain identifying info
        phiPatterns.add(Pattern.compile("\\b(?:Study|Exam|Protocol)[:\\s]+.{5,}\\b", Pattern.CASE_INSENSITIVE));
    }

    /**
     * Check if the given text contains potential PHI.
     *
     * @param text Text to check
     * @return true if the text matches any PHI pattern
     */
    public boolean isPotentialPhi(String text) {
        if (text == null || text.trim().length() < 2) {
            return false;
        }

        String trimmed = text.trim();

        // Check against all PHI patterns
        for (Pattern pattern : phiPatterns) {
            if (pattern.matcher(trimmed).find()) {
                return true;
            }
        }

        // Check for capitalized names (2+ words starting with capitals)
        String[] words = trimmed.split("\\s+");
        if (words.length >= 2) {
            boolean allCapitalized = true;
            for (String word : words) {
                if (word.isEmpty() || !Character.isUpperCase(word.charAt(0))) {
                    allCapitalized = false;
                    break;
                }
            }
            if (allCapitalized && trimmed.matches("^[A-Za-z\\s]+$")) {
                return true; // Looks like a name (e.g., "John Smith")
            }
        }

        // Check for long digit sequences (potential IDs)
        if (trimmed.matches(".*\\d{5,}.*")) {
            return true;
        }

        return false;
    }

    /**
     * Get all PHI matches in the text with their types.
     *
     * @param text Text to analyze
     * @return List of matched PHI patterns with descriptions
     */
    public List<PhiMatch> findAllMatches(String text) {
        List<PhiMatch> matches = new ArrayList<>();

        if (text == null || text.trim().isEmpty()) {
            return matches;
        }

        String trimmed = text.trim();

        // Check each pattern and record matches
        String[] patternNames = {
            "Name", "Date", "Date", "MRN", "SSN", "Phone", "Accession",
            "Institution", "Patient", "DOB", "Age", "Sex", "Weight", "Height",
            "Physician", "Study"
        };

        for (int i = 0; i < phiPatterns.size() && i < patternNames.length; i++) {
            Pattern pattern = phiPatterns.get(i);
            java.util.regex.Matcher matcher = pattern.matcher(trimmed);
            while (matcher.find()) {
                matches.add(new PhiMatch(patternNames[i], matcher.group(), matcher.start(), matcher.end()));
            }
        }

        return matches;
    }

    /**
     * Represents a PHI pattern match.
     */
    public static class PhiMatch {
        private final String type;
        private final String matchedText;
        private final int start;
        private final int end;

        public PhiMatch(String type, String matchedText, int start, int end) {
            this.type = type;
            this.matchedText = matchedText;
            this.start = start;
            this.end = end;
        }

        public String getType() { return type; }
        public String getMatchedText() { return matchedText; }
        public int getStart() { return start; }
        public int getEnd() { return end; }

        @Override
        public String toString() {
            return String.format("PhiMatch{type='%s', text='%s', pos=%d-%d}",
                type, matchedText, start, end);
        }
    }
}
