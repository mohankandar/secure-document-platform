package com.securedoc.platform.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record PackageDetailResponse(
        String packageId,
        String packageType,
        String status,
        Map<String, String> metadata,
        List<DocumentSummaryResponse> documents,
        Instant createdAt,
        Instant updatedAt
) {
}
