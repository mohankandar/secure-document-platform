package com.securedoc.platform.application.review.service;

import java.util.regex.Pattern;

/**
 * Sanitizes reviewer-provided text (comments, reasons) before storage.
 * Masks/removes common sensitive patterns to prevent accidental PII persistence.
 *
 * <p>Applied before passing text to domain methods, audit, or outbox.
 * Never stores raw reviewer input anywhere.
 */
public final class ReviewTextSanitizer {

    private ReviewTextSanitizer() {
        // utility
    }

    // SSN pattern: 3-2-4 digits with separators
    private static final Pattern SSN_PATTERN = Pattern.compile("\\b\\d{3}[- ]\\d{2}[- ]\\d{4}\\b");

    // Email pattern
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");

    // Phone number patterns (US formats)
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "\\b(?:\\+1[- ]?)?\\(?\\d{3}\\)?[- ]?\\d{3}[- ]?\\d{4}\\b");

    // Long digit sequences (account/loan numbers, 8+ digits)
    private static final Pattern LONG_NUMBER_PATTERN = Pattern.compile("\\b\\d{8,}\\b");

    // Windows file paths
    private static final Pattern WINDOWS_PATH_PATTERN = Pattern.compile("[A-Za-z]:\\\\[^\\s]+");

    // Unix file paths with 3+ segments
    private static final Pattern UNIX_PATH_PATTERN = Pattern.compile("/[^\\s]*(/[^\\s]+){2,}");

    // Stack trace lines
    private static final Pattern STACK_TRACE_PATTERN = Pattern.compile(
            "at [a-zA-Z0-9$.]+\\([^)]*\\)");

    // Fully qualified Java class names (com.foo.bar.ClassName)
    private static final Pattern CLASS_NAME_PATTERN = Pattern.compile(
            "\\b[a-z]+\\.[a-z]+\\.[a-z]+\\.[A-Z][a-zA-Z0-9$.]*");

    /**
     * Sanitizes a review comment. Max 500 chars after sanitization.
     *
     * @param raw the raw reviewer comment
     * @return sanitized, truncated comment safe for storage
     */
    public static String sanitizeComment(String raw) {
        return sanitize(raw, 500, "Reviewer comment sanitized");
    }

    /**
     * Sanitizes a finding review reason. Max 200 chars after sanitization.
     *
     * @param raw the raw reviewer reason
     * @return sanitized, truncated reason safe for storage
     */
    public static String sanitizeReason(String raw) {
        return sanitize(raw, 200, "Reviewer reason sanitized");
    }

    private static String sanitize(String raw, int maxLength, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }

        String cleaned = raw;

        // Mask sensitive patterns
        cleaned = SSN_PATTERN.matcher(cleaned).replaceAll("[REDACTED_SSN]");
        cleaned = EMAIL_PATTERN.matcher(cleaned).replaceAll("[REDACTED_EMAIL]");
        cleaned = PHONE_PATTERN.matcher(cleaned).replaceAll("[REDACTED_PHONE]");
        cleaned = LONG_NUMBER_PATTERN.matcher(cleaned).replaceAll("[REDACTED_NUMBER]");
        cleaned = WINDOWS_PATH_PATTERN.matcher(cleaned).replaceAll("[REDACTED_PATH]");
        cleaned = UNIX_PATH_PATTERN.matcher(cleaned).replaceAll("[REDACTED_PATH]");
        cleaned = STACK_TRACE_PATTERN.matcher(cleaned).replaceAll("[REDACTED_TRACE]");
        cleaned = CLASS_NAME_PATTERN.matcher(cleaned).replaceAll("[REDACTED_CLASS]");

        // Normalize whitespace
        cleaned = cleaned.replaceAll("\\s+", " ").strip();

        // If nothing meaningful remains after sanitization, use fallback
        if (cleaned.isBlank() || cleaned.equals("[REDACTED_SSN]") || cleaned.equals("[REDACTED_EMAIL]")
                || cleaned.equals("[REDACTED_PHONE]") || cleaned.equals("[REDACTED_NUMBER]")
                || cleaned.equals("[REDACTED_PATH]")) {
            return fallback;
        }

        // Truncate
        if (cleaned.length() > maxLength) {
            cleaned = cleaned.substring(0, maxLength);
        }

        return cleaned;
    }
}
