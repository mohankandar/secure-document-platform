package com.securedoc.platform.application.detection.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Task 0105, step 2b: where a value the text model named stands in the page's words, when it does not stand there exactly
 * once. Every place of it is covered: the same value twice on a page (a form field's drawn and stored text, a value
 * repeated in a footer) is the same value. Then, for a value with digits, the page's words are read as OCR reads them:
 * {@code O} for {@code 0}, {@code l}, {@code I} or {@code |} for {@code 1}, and spaces, dashes, dots and slashes between
 * the characters left out. Offsets only; never a value in a log.
 */
final class ValuePlaces {

    /** Shorter than this (after separators are left out), a value is too common to place by matching. */
    static final int MIN_MATCHED = 4;

    private ValuePlaces() {
    }

    /** Every place of the value, exact first; empty when it is nowhere. Each place is [start, end). */
    static List<int[]> of(String text, String value) {
        if (text == null || value == null || value.isBlank()) return List.of();
        List<int[]> exact = exact(text, value);
        if (!exact.isEmpty()) return exact;
        return value.chars().anyMatch(Character::isDigit) ? matched(text, value) : List.of();
    }

    /** Every exact place that is not inside a longer word or number. */
    static List<int[]> exact(String text, String value) {
        List<int[]> places = new ArrayList<>();
        for (int at = text.indexOf(value); at >= 0; at = text.indexOf(value, at + 1)) {
            int end = at + value.length();
            if (bounded(text, at, end)) places.add(new int[] {at, end});
        }
        return places;
    }

    /** As OCR reads it: confusions read as digits, separators left out. */
    static List<int[]> matched(String text, String value) {
        StringBuilder wanted = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = normal(value.charAt(i));
            if (c != 0) wanted.append(c);
        }
        if (wanted.length() < MIN_MATCHED) return List.of();
        StringBuilder page = new StringBuilder(text.length());
        int[] from = new int[text.length()];
        for (int i = 0; i < text.length(); i++) {
            char c = normal(text.charAt(i));
            if (c == 0) continue;
            from[page.length()] = i;
            page.append(c);
        }
        List<int[]> places = new ArrayList<>();
        String needle = wanted.toString();
        for (int at = page.indexOf(needle); at >= 0; at = page.indexOf(needle, at + 1)) {
            int start = from[at];
            int end = from[at + needle.length() - 1] + 1;
            if (bounded(text, start, end)) places.add(new int[] {start, end});
        }
        return places;
    }

    /** 0 for a separator; a confusion as the digit it stands for; anything else unchanged, in capitals. */
    private static char normal(char c) {
        if (Character.isWhitespace(c) || c == '-' || c == '–' || c == '—' || c == '.' || c == '/') return 0;
        if (c == 'O' || c == 'o') return '0';
        if (c == 'l' || c == 'I' || c == '|') return '1';
        return Character.toUpperCase(c);
    }

    /** Not part of a longer word or number on either side. */
    private static boolean bounded(String text, int start, int end) {
        boolean before = start == 0 || !Character.isLetterOrDigit(text.charAt(start - 1));
        boolean after = end >= text.length() || !Character.isLetterOrDigit(text.charAt(end));
        return before && after;
    }
}
