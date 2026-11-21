package com.securedoc.platform.domain.pipeline;

/** An export destination's state (design section 5). */
public enum DestinationState {
    WORKING,
    /** Transient failures in a row: its jobs wait without using attempts; a probe brings it back. */
    PAUSED,
    /** Sign-in refused or credential missing: its jobs wait until IT sets it back to WORKING. */
    NEEDS_IT
}
