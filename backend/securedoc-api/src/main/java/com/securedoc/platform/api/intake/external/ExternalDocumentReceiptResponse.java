package com.securedoc.platform.api.intake.external;

import com.securedoc.platform.application.intake.external.ExternalDocumentReceipt;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ExternalDocumentReceiptResponse(
        UUID packageId,
        UUID documentId,
        String externalSourceDocumentId,
        String sha256,
        boolean duplicateAcknowledgement,
        String intakeStatus,
        int expectedDocuments,
        int receivedDocuments,
        List<String> missingExternalSourceDocumentIds,
        Instant expiresAt) {
    public static ExternalDocumentReceiptResponse from(ExternalDocumentReceipt value) {
        return new ExternalDocumentReceiptResponse(value.packageId(), value.documentId(), value.externalSourceDocumentId(),
                value.sha256(), value.duplicateAcknowledgement(), value.intakeStatus().name(), value.expectedDocuments(),
                value.receivedDocuments(), value.missingExternalSourceDocumentIds(), value.expiresAt());
    }
}
