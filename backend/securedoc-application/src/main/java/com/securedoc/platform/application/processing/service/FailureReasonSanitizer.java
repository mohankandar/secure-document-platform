package com.securedoc.platform.application.processing.service;

/**
 * Sanitizes processing failure reasons before they are stored in audit/outbox records.
 *
 * <p>Rules:
 * <ul>
 *   <li>Never store raw exception messages, stack traces, or internal class names</li>
 *   <li>Never store file paths, OCR text, document content, or PII</li>
 *   <li>Truncate to a safe maximum length</li>
 *   <li>Use safe category prefixes where possible</li>
 * </ul>
 */
public final class FailureReasonSanitizer {

    private static final int MAX_LENGTH = 200;

    private FailureReasonSanitizer() {
        // utility
    }

    /**
     * Sanitizes a failure reason string for safe storage in audit/outbox.
     *
     * @param raw the raw failure reason (may contain sensitive data)
     * @return a sanitized, truncated, safe string
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Unknown processing failure";
        }

        String cleaned = raw.strip();

        // Strip anything that looks like a file path
        cleaned = cleaned.replaceAll("[A-Za-z]:\\\\[^\\s]+", "[path-redacted]");
        cleaned = cleaned.replaceAll("/[^\\s]*(/[^\\s]+){2,}", "[path-redacted]");

        // Strip anything that looks like a stack trace line
        cleaned = cleaned.replaceAll("at [a-zA-Z0-9$.]+\\([^)]*\\)", "[trace-redacted]");

        // Strip potential class names with package paths (e.g., com.foo.bar.SomeClass)
        cleaned = cleaned.replaceAll("\\b[a-z]+\\.[a-z]+\\.[a-z]+\\.[A-Z][a-zA-Z0-9$.]*", "[class-redacted]");

        // Truncate
        if (cleaned.length() > MAX_LENGTH) {
            cleaned = cleaned.substring(0, MAX_LENGTH) + "...[truncated]";
        }

        return cleaned;
    }
}
