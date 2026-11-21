package com.securedoc.platform.api.intake.external;

import com.securedoc.platform.application.intake.external.ExternalIntakeSnapshot;
import com.securedoc.platform.application.intake.external.IntakeWarning;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Intake status. {@code warnings} lists what was taken in with a warning, for example LV-INT-005 "Unknown sensitivity
 * level, taken as Standard" (task 0021); it is empty when there is nothing to say and on status reads.
 */
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
        Instant expiredAt,
        List<Warning> warnings) {

    public record Warning(String code, String message, String scope, String externalSourceDocumentId) {
        static Warning from(IntakeWarning w) {
            return new Warning(w.code(), w.message(), w.scope(), w.externalSourceDocumentId());
        }
    }

    public static ExternalIntakeResponse from(ExternalIntakeSnapshot value) {
        return new ExternalIntakeResponse(value.packageId(), value.externalSourcePackageId(), value.sourceRevision(),
                value.attemptNumber(), value.intakeStatus().name(), value.packageStatus().name(), value.expectedDocuments(),
                value.receivedDocuments(), value.missingExternalSourceDocumentIds(), value.createdAt(), value.expiresAt(),
                value.acceptedAt(), value.expiredAt(), value.warnings().stream().map(Warning::from).toList());
    }
}
