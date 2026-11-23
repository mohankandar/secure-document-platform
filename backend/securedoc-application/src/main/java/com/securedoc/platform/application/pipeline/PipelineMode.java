package com.securedoc.platform.application.pipeline;

import java.util.Locale;

/** {@code securedoc.processing.pipeline-mode} (task 0048, staged pipeline P2 part 1). */
public enum PipelineMode {
    /** Today's flow: one worker takes a case and runs text reading, finding and redaction in one go. Default. */
    INLINE,
    /** Text, Find and Redact run as per-document jobs in pipeline_jobs, with gates per case. */
    STAGED;

    /** Blank is the default (inline). Any other value is refused: no silent fallback. */
    public static PipelineMode parse(String value) {
        if (value == null || value.isBlank()) return INLINE;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "inline" -> INLINE;
            case "staged" -> STAGED;
            default -> throw new IllegalArgumentException(
                    "securedoc.processing.pipeline-mode must be inline or staged");
        };
    }
}
