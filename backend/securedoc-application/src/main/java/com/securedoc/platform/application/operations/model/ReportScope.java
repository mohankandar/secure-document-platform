package com.securedoc.platform.application.operations.model;

import com.securedoc.platform.domain.document.SensitivityLevel;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Task 0081: which cases a report counts. {@code sourceSystem} is a connection's id, {@code folder:<key>} or
 * {@code type:<package type>} for cases without one; {@code level} is the case's effective sensitivity level. ALL: every one.
 */
public record ReportScope(String sourceSystem, String level) {
    public static final String ALL = "ALL";
    private static final Pattern SOURCE = Pattern.compile("[A-Za-z0-9:_-]{1,128}");
    public static final ReportScope EVERYTHING = new ReportScope(ALL, ALL);

    public ReportScope {
        sourceSystem = sourceSystem == null || sourceSystem.isBlank() ? ALL : sourceSystem.trim();
        level = level == null || level.isBlank() ? ALL : level.trim().toUpperCase(Locale.ROOT);
        if (!SOURCE.matcher(sourceSystem).matches()) throw new IllegalArgumentException("Unsupported report source system");
        if (!ALL.equals(level)) {
            try {
                SensitivityLevel.valueOf(level);
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException("Unsupported report level");
            }
        }
    }

    public boolean everySource() {
        return ALL.equals(sourceSystem);
    }

    public boolean everyLevel() {
        return ALL.equals(level);
    }
}
