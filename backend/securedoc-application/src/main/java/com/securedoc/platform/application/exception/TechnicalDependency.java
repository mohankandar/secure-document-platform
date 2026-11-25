package com.securedoc.platform.application.exception;

/** External/runtime dependency involved in a technical processing failure. */
public enum TechnicalDependency {
    AI,
    OCR,
    DELIVERY,
    STORAGE,
    /** Task 0060: the key service that opens and protects stored files. */
    KEY_SERVICE,
    /** Task 0060: the erase log every erasure is written to first. */
    ERASE_LOG,
    /** Task 0023: the redaction engine or its safety verification. */
    REDACTION,
    /** Task 0023: starting package processing (dispatch, licence check). */
    PROCESSING,
    UNKNOWN
}
