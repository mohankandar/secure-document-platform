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
    STORAGE_IO,
    UNKNOWN_TECHNICAL_FAILURE
}
