package com.securedoc.platform.application.technical.service;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses only the stable, sanitized technical-classification suffix written by TechnicalFailureClassifier. */
final class TechnicalFailureAuditParser {
    private static final Pattern CLASSIFIED = Pattern.compile(
            "^(.*?):\\s*dependency=([A-Z_]+),\\s*code=([A-Z_]+),\\s*retryable=(true|false),\\s*attempts=(\\d+)\\s*$",
            Pattern.CASE_INSENSITIVE);

    private TechnicalFailureAuditParser() {}

    static Parsed parse(String detail) {
        if (detail == null) return Parsed.unknown();
        Matcher matcher = CLASSIFIED.matcher(detail.trim());
        if (!matcher.matches()) return Parsed.unknownWithPrefix(detail.trim());
        String prefix = matcher.group(1).trim();
        String dependency = matcher.group(2).toUpperCase(Locale.ROOT);
        String code = matcher.group(3).toUpperCase(Locale.ROOT);
        boolean retryable = Boolean.parseBoolean(matcher.group(4));
        int attempts;
        try {
            attempts = Integer.parseInt(matcher.group(5));
        } catch (NumberFormatException ex) {
            attempts = 1;
        }
        return new Parsed(prefix, dependency, code, retryable, Math.max(1, attempts), true);
    }

    record Parsed(String prefix, String dependency, String code, boolean retryable, int attempts, boolean classified) {
        static Parsed unknown() {
            return new Parsed("Technical processing failure", "UNKNOWN", "UNKNOWN_TECHNICAL_FAILURE", false, 1, false);
        }
        static Parsed unknownWithPrefix(String prefix) {
            String safe = prefix == null || prefix.isBlank() ? "Technical processing failure" : prefix;
            return new Parsed(safe, "UNKNOWN", "UNKNOWN_TECHNICAL_FAILURE", false, 1, false);
        }
    }
}
