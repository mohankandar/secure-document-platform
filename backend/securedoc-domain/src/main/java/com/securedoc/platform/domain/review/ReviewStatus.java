package com.securedoc.platform.domain.review;

/**
 * Lifecycle status of a review task.
 */
public enum ReviewStatus {

    /** Task created, not yet assigned. */
    PENDING,

    /** Task assigned to a reviewer. */
    ASSIGNED,

    /** Reviewer is actively working on the task. */
    IN_PROGRESS,

    /** Review completed with approval. */
    APPROVED,

    /** Review completed with rejection. */
    REJECTED,

    /** Task escalated to a higher authority. */
    ESCALATED,

    /** Task cancelled. */
    CANCELLED
}
