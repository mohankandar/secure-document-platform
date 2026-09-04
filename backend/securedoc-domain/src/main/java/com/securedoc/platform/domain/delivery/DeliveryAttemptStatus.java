package com.securedoc.platform.domain.delivery;

public enum DeliveryAttemptStatus {
    STARTED,
    SUCCEEDED,
    FAILED_RETRYABLE,
    FAILED_ATTENTION
}
