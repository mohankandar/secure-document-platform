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
    UNKNOWN_TECHNICAL_FAILURE;

    /** The stable LexaVault code shown to IT Admin, where one is assigned (task 0021). */
    public String publicCode() {
        return this == DELIVERY_SENSITIVITY_REFUSED ? "LV-DLV-003" : null;
    }
}
