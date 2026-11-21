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
    /** Task 0023 C3, verification: a redaction box is not dark enough to cover its region. */
    REDACTION_REGION_NOT_COVERED,
    /** Task 0023 C3, verification: the redacted output could not be read back. */
    REDACTION_OUTPUT_UNPARSABLE,
    UNKNOWN_TECHNICAL_FAILURE;

    /** The stable LexaVault code shown to IT Admin, where one is assigned (task 0021). */
    public String publicCode() {
        return this == DELIVERY_SENSITIVITY_REFUSED ? "LV-DLV-003" : null;
    }
}
