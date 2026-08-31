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

    /** Straight-through processing and delivery completed successfully. */
    COMPLETED,

    /** Processing paused because one or more findings require human review. */
    PENDING_REVIEW,

    /** Human review in progress. */
    IN_REVIEW,

    /** All document work is complete but policy requires final approval before delivery. */
    PENDING_FINAL_APPROVAL,

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
