package com.securedoc.platform.api.dto;

import java.time.Instant;
import java.util.List;

public record DashboardItemResponse(
        String packageId,
        String packageType,
        String status,
        String stage,
        String attention,
        List<DashboardIdentifierResponse> identifiers,
        int documentCount,
        int successfulExportCycles,
        Instant lastExportedAt,
        Instant originalDeleteAt,
        Instant technicalHoldUntil,
        Instant createdAt,
        Instant updatedAt
) {}
