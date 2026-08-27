package com.securedoc.platform.api.dto;

import java.time.Instant;

public record PackageSummaryResponse(
        String packageId,
        String packageType,
        String status,
        int documentCount,
        Instant createdAt,
        Instant updatedAt
) {
}
