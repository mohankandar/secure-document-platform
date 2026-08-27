package com.securedoc.platform.domain.event;

import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.Objects;

/**
 * Event raised when text extraction completes successfully for a document.
 * Contains IDs and safe metadata only — never extracted text content.
 */
public record TextExtractionCompletedEvent(
        DocumentId documentId,
        PackageId packageId,
        TenantId tenantId,
        ArtifactId extractedTextArtifactId,
        int pageCount,
        long characterCount,
        CorrelationId correlationId,
        Instant occurredAt
) implements DomainEvent {

    public TextExtractionCompletedEvent {
        Objects.requireNonNull(documentId, "DocumentId must not be null");
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(extractedTextArtifactId, "ExtractedTextArtifactId must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
        Objects.requireNonNull(occurredAt, "OccurredAt must not be null");
    }

    public static TextExtractionCompletedEvent of(DocumentId documentId, PackageId packageId,
                                                   TenantId tenantId, ArtifactId extractedTextArtifactId,
                                                   int pageCount, long characterCount,
                                                   CorrelationId correlationId) {
        return new TextExtractionCompletedEvent(documentId, packageId, tenantId,
                extractedTextArtifactId, pageCount, characterCount, correlationId, Instant.now());
    }

    @Override
    public String eventType() {
        return "TextExtractionCompleted";
    }
}
