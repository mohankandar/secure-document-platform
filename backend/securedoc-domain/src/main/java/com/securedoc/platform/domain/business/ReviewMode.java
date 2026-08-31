package com.securedoc.platform.domain.business;

/**
 * Business review posture for sensitive-content findings.
 *
 * <p>UNCERTAIN_ONLY is the recommended straight-through default. REVIEW_ALL is a
 * temporary trust-building mode that routes every enabled finding to Manual Review.
 * Final-approval-after-redaction is intentionally deferred until the delivery/retention
 * lifecycle owns a proper pre-delivery hold state.
 */
public enum ReviewMode {
    UNCERTAIN_ONLY,
    REVIEW_ALL
}
