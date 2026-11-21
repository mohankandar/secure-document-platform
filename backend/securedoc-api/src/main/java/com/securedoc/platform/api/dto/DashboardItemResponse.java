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
        Instant updatedAt,
        // Task 0028: the export state for the "Exporting" status (task 0029): NONE, READY, DESTINATION_PAUSED,
        // WAITING_FOR_YOU, NEEDS_IT or EXPORTED. NONE in inline export mode (there are no export jobs).
        String exportState
) {}
