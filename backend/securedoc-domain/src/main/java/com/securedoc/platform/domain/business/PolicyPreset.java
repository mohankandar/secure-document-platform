package com.securedoc.platform.domain.business;

import java.util.Objects;

/**
 * The named preset a saved sensitive-data policy matches (task 0016, design "Safe policy: presets and preview").
 * A preset covers only the review mode and the automatic-redaction threshold; sensitive types and the
 * labeled-last-four-SSN switch are never part of it. Worked out on the server from the saved values only, so the
 * audit trail, U023 and the compliance report all use this one rule.
 *
 * <ul>
 *   <li>{@link #STRICT}: review mode {@code REVIEW_ALL}, at any threshold;</li>
 *   <li>{@link #BALANCED}: {@code UNCERTAIN_ONLY} at exactly 0.80;</li>
 *   <li>{@link #LIGHT}: {@code UNCERTAIN_ONLY} at exactly 0.70;</li>
 *   <li>{@link #CUSTOM}: anything else.</li>
 * </ul>
 */
public enum PolicyPreset {
    STRICT,
    BALANCED,
    LIGHT,
    CUSTOM;

    static final double BALANCED_THRESHOLD = 0.80;
    static final double LIGHT_THRESHOLD = 0.70;
    /** Thresholds are saved with two decimals; this only absorbs binary floating-point noise. */
    private static final double SAME = 1e-9;

    public static PolicyPreset of(ReviewMode reviewMode, double autoRedactThreshold) {
        Objects.requireNonNull(reviewMode, "reviewMode must not be null");
        if (reviewMode == ReviewMode.REVIEW_ALL) return STRICT;
        if (Math.abs(autoRedactThreshold - BALANCED_THRESHOLD) < SAME) return BALANCED;
        if (Math.abs(autoRedactThreshold - LIGHT_THRESHOLD) < SAME) return LIGHT;
        return CUSTOM;
    }

    public static PolicyPreset of(TenantRedactionPolicy policy) {
        Objects.requireNonNull(policy, "policy must not be null");
        return of(policy.reviewMode(), policy.autoRedactThreshold());
    }
}
