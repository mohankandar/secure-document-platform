package com.securedoc.platform.application.pipeline;

import java.util.Locale;

/** {@code securedoc.processing.export-mode} (task 0027). */
public enum ExportMode {
    /** Today's behaviour: the case is exported at the end of processing, with the in-adapter retries. Default. */
    INLINE,
    /** The case is exported by an export job, with retries by code. */
    JOB;

    /** Blank is the default (inline). Any other value is refused: no silent fallback. */
    public static ExportMode parse(String value) {
        if (value == null || value.isBlank()) return INLINE;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "inline" -> INLINE;
            case "job" -> JOB;
            default -> throw new IllegalArgumentException(
                    "securedoc.processing.export-mode must be inline or job");
        };
    }
}
