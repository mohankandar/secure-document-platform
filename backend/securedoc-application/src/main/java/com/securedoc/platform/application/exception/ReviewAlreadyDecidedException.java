package com.securedoc.platform.application.exception;

/**
 * Task 0023 (D5, #160): a review item was already decided by someone else. The API answers 409 with
 * {@link #ERROR_CODE} and the fixed {@link #MESSAGE}; the Review screen matches on the code and moves on.
 */
public final class ReviewAlreadyDecidedException extends ApplicationException {
    public static final String ERROR_CODE = "REVIEW_ALREADY_DECIDED";
    public static final String MESSAGE = "This item was already decided by someone else.";

    public ReviewAlreadyDecidedException() {
        super(ERROR_CODE, MESSAGE);
    }

    public ReviewAlreadyDecidedException(Throwable cause) {
        super(ERROR_CODE, MESSAGE, cause);
    }
}
