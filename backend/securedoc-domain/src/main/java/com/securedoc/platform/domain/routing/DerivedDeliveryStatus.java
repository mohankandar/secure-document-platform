package com.securedoc.platform.domain.routing;

/** Delivery lifecycle for a derived redacted document. Independent of the master SDV export status. */
public enum DerivedDeliveryStatus {
    PENDING,
    DELIVERING,
    RETRY_PENDING,
    DELIVERED,
    ATTENTION_REQUIRED
}
