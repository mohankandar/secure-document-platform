package com.securedoc.platform.domain.document;

/**
 * Lifecycle status of a document package.
 */
public enum PackageStatus {

    /** Package created, awaiting documents. */
    CREATED,

    /** Documents uploaded, awaiting processing. */
    SUBMITTED,

    /** Processing in progress (OCR, detection, redaction). */
    PROCESSING,

    /** Processing complete, awaiting human review. */
    PENDING_REVIEW,

    /** Human review in progress. */
    IN_REVIEW,

    /** Review approved, ready for vault registration. */
    APPROVED,

    /** Registered in the secure vault. */
    VAULTED,

    /** Package rejected during review. */
    REJECTED,

    /** Package processing failed. */
    FAILED,

    /** Package cancelled by user or system. */
    CANCELLED
}
