package com.securedoc.platform.domain.vault;

/**
 * Lifecycle status of a vault access request.
 */
public enum AccessRequestStatus {

    /** Request submitted, awaiting approval. */
    PENDING,

    /** Request approved — access granted. */
    APPROVED,

    /** Request denied. */
    DENIED,

    /** Request expired before decision. */
    EXPIRED,

    /** Request revoked after approval. */
    REVOKED
}
