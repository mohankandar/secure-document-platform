package com.securedoc.platform.api.dto;

import java.time.Instant;
import java.util.List;

public record PackageSummaryResponse(
        String packageId,
        String packageType,
        String status,
        List<BusinessIdentifierResponse> businessIdentifiers,
        int documentCount,
        Instant createdAt,
        Instant updatedAt
) {}
