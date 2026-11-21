package com.securedoc.platform.application.exception;

/** External/runtime dependency involved in a technical processing failure. */
public enum TechnicalDependency {
    AI,
    OCR,
    DELIVERY,
    STORAGE,
    /** Task 0023: the redaction engine or its safety verification. */
    REDACTION,
    /** Task 0023: starting package processing (dispatch, licence check). */
    PROCESSING,
    UNKNOWN
}
