package com.securedoc.platform.application.pipeline;

import java.util.Locale;

/**
 * {@code securedoc.processing.pipeline-mode} (task 0048). Task 0121 (owner 2026-11-28): staged only. Text, Find, Vision,
 * Redact and Export run as their own jobs in pipeline_jobs, with gates per case. The inline pipeline is removed.
 */
public enum PipelineMode {
    STAGED;

    /** Empty or {@code staged}. Anything else, {@code inline} included, is refused: no silent fallback. */
    public static PipelineMode parse(String value) {
        if (value == null || value.isBlank()) return STAGED;
        String given = value.trim().toLowerCase(Locale.ROOT);
        if (given.equals("staged")) return STAGED;
        if (given.equals("inline")) {
            throw new IllegalArgumentException("securedoc.processing.pipeline-mode=inline was removed (task 0121): "
                    + "leave it empty or set staged");
        }
        throw new IllegalArgumentException("securedoc.processing.pipeline-mode must be staged (or empty)");
    }
}
