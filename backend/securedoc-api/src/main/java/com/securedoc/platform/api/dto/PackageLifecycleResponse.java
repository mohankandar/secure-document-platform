package com.securedoc.platform.api.dto;

import com.securedoc.platform.application.lifecycle.result.PackageLifecycleSummary;

import java.time.Instant;

/** Stable API DTO that keeps domain value objects out of the JSON contract. */
public record PackageLifecycleResponse(
        String packageId,
        int successfulExportCycles,
        int maximumExportCycles,
        boolean canReprocess,
        boolean finalApprovalRequired,
        boolean finalApprovalGranted,
        Instant lastExportedAt,
        Instant originalDeleteAt,
        Instant redactedDeleteAt,
        Instant technicalHoldUntil,
        Instant redactedPurgedAt,
        Instant originalPurgedAt,
        Instant purgedAt,
        String lastDestinationType
) {
    public static PackageLifecycleResponse from(PackageLifecycleSummary summary) {
        return new PackageLifecycleResponse(
                summary.packageId().toString(),
                summary.successfulExportCycles(),
                summary.maximumExportCycles(),
                summary.canReprocess(),
                summary.finalApprovalRequired(),
                summary.finalApprovalGranted(),
                summary.lastExportedAt(),
                summary.originalDeleteAt(),
                summary.redactedDeleteAt(),
                summary.technicalHoldUntil(),
                summary.redactedPurgedAt(),
                summary.originalPurgedAt(),
                summary.purgedAt(),
                summary.lastDestinationType());
    }
}
