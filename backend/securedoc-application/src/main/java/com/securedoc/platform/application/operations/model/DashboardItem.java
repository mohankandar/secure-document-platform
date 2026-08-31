package com.securedoc.platform.application.operations.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Read-optimized package/intake row for the shared dashboard. */
public record DashboardItem(
        UUID packageId,
        String packageType,
        String status,
        DashboardStage stage,
        String attention,
        List<DashboardIdentifierValue> identifiers,
        int documentCount,
        int successfulExportCycles,
        Instant lastExportedAt,
        Instant originalDeleteAt,
        Instant technicalHoldUntil,
        Instant createdAt,
        Instant updatedAt
) {
    public DashboardItem {
        Objects.requireNonNull(packageId);
        Objects.requireNonNull(packageType);
        Objects.requireNonNull(status);
        Objects.requireNonNull(stage);
        attention = attention == null ? "NONE" : attention;
        identifiers = identifiers == null ? List.of() : List.copyOf(identifiers);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(updatedAt);
    }
}
