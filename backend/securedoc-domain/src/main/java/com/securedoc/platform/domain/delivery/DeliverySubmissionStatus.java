package com.securedoc.platform.domain.delivery;

/** Durable state of one master redacted-package delivery cycle. */
public enum DeliverySubmissionStatus {
    PENDING,
    DELIVERING,
    PARTIALLY_ACKNOWLEDGED,
    RETRY_PENDING,
    DELIVERED,
    ATTENTION_REQUIRED
}
