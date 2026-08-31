package com.securedoc.platform.application.lifecycle.result;

import com.securedoc.platform.domain.identity.PackageId;

import java.time.Instant;

public record PackageLifecycleSummary(
        PackageId packageId,
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
}
