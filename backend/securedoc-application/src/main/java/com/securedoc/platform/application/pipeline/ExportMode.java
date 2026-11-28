package com.securedoc.platform.application.pipeline;

import java.util.Locale;

/**
 * {@code securedoc.processing.export-mode} (task 0027). Task 0121 (owner 2026-11-28): export is always an export job,
 * with retries by code. Export at the end of processing (inline) is removed.
 */
public enum ExportMode {
    JOB;

    /** Empty or {@code job}. Anything else, {@code inline} included, is refused: no silent fallback. */
    public static ExportMode parse(String value) {
        if (value == null || value.isBlank()) return JOB;
        String given = value.trim().toLowerCase(Locale.ROOT);
        if (given.equals("job")) return JOB;
        if (given.equals("inline")) {
            throw new IllegalArgumentException("securedoc.processing.export-mode=inline was removed (task 0121): "
                    + "leave it empty or set job");
        }
        throw new IllegalArgumentException("securedoc.processing.export-mode must be job (or empty)");
    }
}
