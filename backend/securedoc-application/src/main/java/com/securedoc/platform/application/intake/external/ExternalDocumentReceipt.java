package com.securedoc.platform.application.intake.external;

import com.securedoc.platform.domain.intake.ExternalIntakeStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ExternalDocumentReceipt(
        UUID packageId,
        UUID documentId,
        String externalSourceDocumentId,
        String sha256,
        boolean duplicateAcknowledgement,
        ExternalIntakeStatus intakeStatus,
        int expectedDocuments,
        int receivedDocuments,
        List<String> missingExternalSourceDocumentIds,
        Instant expiresAt) {
}
