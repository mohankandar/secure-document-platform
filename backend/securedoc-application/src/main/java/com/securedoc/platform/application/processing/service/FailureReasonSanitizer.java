package com.securedoc.platform.application.processing.service;

/**
 * Provides controlled safe failure reason messages for audit/outbox persistence.
 *
 * <p>Security rule: arbitrary exception text is NEVER persisted.
 * Only controlled, application-generated reason strings are allowed.
 *
 * <p>Known safe application-generated reasons (produced by service code, not exceptions):
 * <ul>
 *   <li>"No embedded text found in document"</li>
 *   <li>"No active findings available for redaction"</li>
 *   <li>"Redaction location could not be determined"</li>
 * </ul>
 *
 * <p>For exception-originated failures, use the category methods instead of sanitize().
 */
public final class FailureReasonSanitizer {

    private static final int MAX_LENGTH = 200;

    // Controlled safe reason strings that are known to be application-generated
    private static final String REASON_NO_TEXT = "No embedded text found in document";
    private static final String REASON_NO_FINDINGS = "No active findings available for redaction";
    private static final String REASON_NO_LOCATION = "Redaction location could not be determined";

    private FailureReasonSanitizer() {
        // utility
    }

    /**
     * Maps a caller-provided reason to a safe output.
     * Only known application-controlled reasons pass through.
     * Unknown/arbitrary text (e.g., exception messages) is replaced with a safe default.
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Document processing failed";
        }

        String trimmed = raw.strip();

        // Allow known safe application-generated reasons
        if (isKnownSafeReason(trimmed)) {
            return truncate(trimmed);
        }

        // All other input (including arbitrary exception messages) gets a safe default
        return "Document processing failed";
    }

    /** Safe failure reason for text extraction errors. */
    public static String textExtractionFailed() {
        return "Text extraction failed";
    }

    /** Safe failure reason for sensitive data detection errors. */
    public static String sensitiveDataDetectionFailed() {
        return "Sensitive data detection failed";
    }

    /** Safe failure reason for redaction errors. */
    public static String redactionFailed() {
        return "Redaction failed";
    }

    /** Safe failure reason for generic document processing errors. */
    public static String documentProcessingFailed() {
        return "Document processing failed";
    }

    private static boolean isKnownSafeReason(String reason) {
        return reason.equals(REASON_NO_TEXT)
                || reason.equals(REASON_NO_FINDINGS)
                || reason.equals(REASON_NO_LOCATION);
    }

    private static String truncate(String value) {
        if (value.length() > MAX_LENGTH) {
            return value.substring(0, MAX_LENGTH);
        }
        return value;
    }
}
