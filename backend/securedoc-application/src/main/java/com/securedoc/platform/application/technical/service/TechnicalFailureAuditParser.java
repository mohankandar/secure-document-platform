package com.securedoc.platform.application.technical.service;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses only the stable, sanitized technical-classification suffix written by TechnicalFailureClassifier.
 *
 * <p>Task 0025: two forms are read. The current one carries the LexaVault code first ({@code "<prefix>:
 * code=LV-…, detail=<code>, dependency=…, retryable=…, attempts=…"}); records written before task 0025 have
 * {@code "<prefix>: dependency=…, code=<code>, retryable=…, attempts=…"}.
 */
final class TechnicalFailureAuditParser {
    private static final Pattern CLASSIFIED = Pattern.compile(
            "^(.*?):\\s*(?:code=(LV-[A-Z]+-\\d{3}),\\s*detail=([A-Z_]+),\\s*dependency=([A-Z_]+)"
                    + "|dependency=([A-Z_]+),\\s*code=([A-Z_]+)),\\s*retryable=(true|false),\\s*attempts=(\\d+)\\s*$",
            Pattern.CASE_INSENSITIVE);

    private TechnicalFailureAuditParser() {}

    static Parsed parse(String detail) {
        if (detail == null) return Parsed.unknown();
        Matcher matcher = CLASSIFIED.matcher(detail.trim());
        if (!matcher.matches()) return Parsed.unknownWithPrefix(detail.trim());
        String prefix = matcher.group(1).trim();
        boolean current = matcher.group(2) != null;
        String lvCode = current ? matcher.group(2).toUpperCase(Locale.ROOT) : null;
        String code = (current ? matcher.group(3) : matcher.group(6)).toUpperCase(Locale.ROOT);
        String dependency = (current ? matcher.group(4) : matcher.group(5)).toUpperCase(Locale.ROOT);
        boolean retryable = Boolean.parseBoolean(matcher.group(7));
        int attempts;
        try {
            attempts = Integer.parseInt(matcher.group(8));
        } catch (NumberFormatException ex) {
            attempts = 1;
        }
        return new Parsed(prefix, dependency, code, retryable, Math.max(1, attempts), true, lvCode);
    }

    /** {@code lvCode} is the recorded LexaVault code, or null for a record written before task 0025. */
    record Parsed(String prefix, String dependency, String code, boolean retryable, int attempts, boolean classified,
                  String lvCode) {
        static Parsed unknown() {
            return new Parsed("Technical processing failure", "UNKNOWN", "UNKNOWN_TECHNICAL_FAILURE", false, 1, false,
                    null);
        }
        static Parsed unknownWithPrefix(String prefix) {
            String safe = prefix == null || prefix.isBlank() ? "Technical processing failure" : prefix;
            return new Parsed(safe, "UNKNOWN", "UNKNOWN_TECHNICAL_FAILURE", false, 1, false, null);
        }
    }
}
