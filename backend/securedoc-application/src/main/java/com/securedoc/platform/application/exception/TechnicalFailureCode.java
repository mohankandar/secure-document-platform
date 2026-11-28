package com.securedoc.platform.application.exception;

/** Stable, non-sensitive technical reason codes used for classification and audit. */
public enum TechnicalFailureCode {
    AI_TIMEOUT,
    AI_RATE_LIMITED,
    AI_UNAVAILABLE,
    AI_REJECTED,
    AI_INVALID_RESPONSE,
    OCR_TIMEOUT,
    OCR_UNAVAILABLE,
    OCR_PROCESS_FAILED,
    DELIVERY_IO,
    DELIVERY_DESTINATION_INVALID,
    /** Task 0021: the destination refused the sensitivity level sent with the export. Never retried. LV-DLV-003. */
    DELIVERY_SENSITIVITY_REFUSED,
    STORAGE_IO,
    /** Task 0060: the key service can't be reached, is sealed or gave no usable answer. Retryable. LV-SYS-010. */
    KEY_SERVICE_UNAVAILABLE,
    /** Task 0060: the key service refused the app, or the customer's keys are missing or off. Retryable. LV-SYS-011. */
    KEY_SERVICE_REFUSED,
    /** Task 0060: a stored file without a record of its protection, or changed. Never retried. LV-SYS-012. */
    STORED_FILE_REFUSED,
    /** Task 0060: the database was restored and the erase replay has not run. LV-SYS-013. */
    ERASE_REPLAY_REQUIRED,
    /** Task 0060: the erase log can't be used, so nothing is erased. Retryable. LV-SYS-014. */
    ERASE_LOG_UNAVAILABLE,
    /** Task 0023 C1: the case failed to start processing 3 times in a row. Stopped, not retried automatically. */
    PROCESSING_DISPATCH_EXHAUSTED,
    /** Task 0023 C1: the tenant licence does not allow document processing. Stopped at once, not retried. */
    PROCESSING_LICENSE_DENIED,
    /** Task 0023 C3: a confirmed finding has no location on the page, so it cannot be covered. */
    REDACTION_LOCATION_MISSING,
    /** Task 0023 C3: the source page count is unknown, so the redacted output cannot be checked. */
    REDACTION_SOURCE_PAGES_UNKNOWN,
    /** Task 0023 C3: the redacted output does not have the same number of pages as the source. */
    REDACTION_PAGE_COUNT_MISMATCH,
    /** Task 0023 C3: the redaction engine did not apply every expected redaction. */
    REDACTION_TARGETS_NOT_APPLIED,
    /** Task 0023 C3, verification: the redacted output is empty. */
    REDACTION_OUTPUT_EMPTY,
    /** Task 0023 C3, verification: a redacted page still has extractable text. */
    REDACTION_TEXT_LAYER_REMAINS,
    /** Task 0023 C3, verification: the redacted output still has annotations. */
    REDACTION_ANNOTATIONS_REMAIN,
    /** Task 0050: metadata, an attachment, a script, a form, a layer or the like is left in the output. */
    REDACTION_HIDDEN_CONTENT_REMAINS,
    /** Task 0023 C3, verification: a redaction box is not dark enough to cover its region. */
    REDACTION_REGION_NOT_COVERED,
    /** Task 0023 C3, verification: the redacted output could not be read back. */
    REDACTION_OUTPUT_UNPARSABLE,
    /** Task 0044, verification: a value that was redacted appears in the text of an output page (fail closed). */
    REDACTION_VALUE_IN_OUTPUT_TEXT,
    /**
     * Task 0119: the final check still found a value on a page a person finished (a box that did not cover it). Our
     * defect: the document stops and is not delivered, and the page is not sent back to the reviewer.
     */
    REDACTION_VALUE_LEFT_ON_CHECKED_PAGE,
    UNKNOWN_TECHNICAL_FAILURE;

    /** The stable LexaVault code shown to IT Admin, where one is assigned (task 0021). */
    public String publicCode() {
        return this == DELIVERY_SENSITIVITY_REFUSED ? "LV-DLV-003" : null;
    }
}
