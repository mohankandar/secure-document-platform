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
        Instant updatedAt,
        /** Task 0029: NONE, READY, DESTINATION_PAUSED, WAITING_FOR_YOU, NEEDS_IT or EXPORTED (the "Exporting" status). */
        String exportState
) {}
