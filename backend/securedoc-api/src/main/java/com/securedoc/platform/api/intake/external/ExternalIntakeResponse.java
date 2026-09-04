package com.securedoc.platform.api.intake.external;

import com.securedoc.platform.application.intake.external.ExternalIntakeSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ExternalIntakeResponse(
        UUID packageId,
        String externalSourcePackageId,
        String sourceRevision,
        int attemptNumber,
        String intakeStatus,
        String packageStatus,
        int expectedDocuments,
        int receivedDocuments,
        List<String> missingExternalSourceDocumentIds,
        Instant createdAt,
        Instant expiresAt,
        Instant acceptedAt,
        Instant expiredAt) {
    public static ExternalIntakeResponse from(ExternalIntakeSnapshot value) {
        return new ExternalIntakeResponse(value.packageId(), value.externalSourcePackageId(), value.sourceRevision(),
                value.attemptNumber(), value.intakeStatus().name(), value.packageStatus().name(), value.expectedDocuments(),
                value.receivedDocuments(), value.missingExternalSourceDocumentIds(), value.createdAt(), value.expiresAt(),
                value.acceptedAt(), value.expiredAt());
    }
}
