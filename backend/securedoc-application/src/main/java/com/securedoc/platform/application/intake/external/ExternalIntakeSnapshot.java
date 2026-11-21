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
        Instant expiredAt,
        List<IntakeWarning> warnings) {

    public ExternalIntakeSnapshot {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public ExternalIntakeSnapshot(UUID packageId, String externalSourcePackageId, String sourceRevision,
                                  int attemptNumber, ExternalIntakeStatus intakeStatus, PackageStatus packageStatus,
                                  int expectedDocuments, int receivedDocuments,
                                  List<String> missingExternalSourceDocumentIds, Instant createdAt,
                                  Instant expiresAt, Instant acceptedAt, Instant expiredAt) {
        this(packageId, externalSourcePackageId, sourceRevision, attemptNumber, intakeStatus, packageStatus,
                expectedDocuments, receivedDocuments, missingExternalSourceDocumentIds, createdAt, expiresAt,
                acceptedAt, expiredAt, List.of());
    }

    /** The same snapshot carrying the warnings of the create request it answers. */
    public ExternalIntakeSnapshot withWarnings(List<IntakeWarning> value) {
        return new ExternalIntakeSnapshot(packageId, externalSourcePackageId, sourceRevision, attemptNumber,
                intakeStatus, packageStatus, expectedDocuments, receivedDocuments, missingExternalSourceDocumentIds,
                createdAt, expiresAt, acceptedAt, expiredAt, value);
    }
}
