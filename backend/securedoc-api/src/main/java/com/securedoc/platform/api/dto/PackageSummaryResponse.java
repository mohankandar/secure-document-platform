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
        String exportState,
        /**
         * Task 0038: why the case stopped or waits, as an LV code (the screen shows its plain title); null when it
         * neither stopped nor waits, or the reason is not known. A rejected case has none: no rejection reason is stored.
         */
        String stopReasonCode,
        /** Task 0038: the delivery destination's name once one is chosen; null before. */
        String destinationName
) {}
