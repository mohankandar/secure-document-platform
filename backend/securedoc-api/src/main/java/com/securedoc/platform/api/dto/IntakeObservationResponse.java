package com.securedoc.platform.api.dto;

import java.time.Instant;

public record IntakeObservationResponse(
        String status,
        String reasonCode,
        String sourceReference,
        String packageId,
        Instant firstSeenAt,
        Instant visibleAfter,
        Instant updatedAt
) {}
