package com.securedoc.platform.domain.finding;

/**
 * Status of a sensitive-data finding through its lifecycle.
 */
public enum FindingStatus {

    /** Detected by automated analysis, not yet reviewed. */
    DETECTED,

    /** Confirmed as a true positive (by human or policy). */
    CONFIRMED,

    /** Dismissed as a false positive. */
    DISMISSED,

    /** Redaction has been applied for this finding. */
    REDACTED,

    /** Finding was overridden by a reviewer. */
    OVERRIDDEN
}
