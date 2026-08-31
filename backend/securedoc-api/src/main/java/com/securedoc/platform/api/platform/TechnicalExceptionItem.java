package com.securedoc.platform.api.platform;

import java.time.Instant;

public record TechnicalExceptionItem(
        String packageId,
        String reasonCode,
        String status,
        int documentCount,
        Instant failedAt,
        Instant technicalHoldUntil) {
}
