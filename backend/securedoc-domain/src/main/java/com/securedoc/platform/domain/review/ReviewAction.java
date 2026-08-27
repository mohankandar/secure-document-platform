package com.securedoc.platform.domain.review;

/**
 * Actions that can be taken during a review.
 */
public enum ReviewAction {

    /** Assign the task to a reviewer. */
    ASSIGN,

    /** Start working on the review. */
    START,

    /** Approve the reviewed content. */
    APPROVE,

    /** Reject the reviewed content. */
    REJECT,

    /** Escalate to a higher authority. */
    ESCALATE,

    /** Add feedback without changing status. */
    COMMENT,

    /** Cancel the review task. */
    CANCEL
}
