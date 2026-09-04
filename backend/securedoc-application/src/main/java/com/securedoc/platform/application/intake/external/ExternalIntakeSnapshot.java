package com.securedoc.platform.application.intake.external;

import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.intake.ExternalIntakeStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ExternalIntakeSnapshot(
        UUID packageId,
        String externalSourcePackageId,
        String sourceRevision,
        int attemptNumber,
        ExternalIntakeStatus intakeStatus,
        PackageStatus packageStatus,
        int expectedDocuments,
        int receivedDocuments,
        List<String> missingExternalSourceDocumentIds,
        Instant createdAt,
        Instant expiresAt,
        Instant acceptedAt,
        Instant expiredAt) {
}
