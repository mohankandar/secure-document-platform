package com.securedoc.platform.domain.event;

import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.FindingId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.vo.Confidence;

import java.time.Instant;
import java.util.Objects;

/**
 * Event raised when a new sensitive-data finding is detected.
 */
public record FindingDetectedEvent(
        FindingId findingId,
        DocumentId documentId,
        TenantId tenantId,
        EntityType entityType,
        Confidence confidence,
        CorrelationId correlationId,
        Instant occurredAt
) implements DomainEvent {

    public FindingDetectedEvent {
        Objects.requireNonNull(findingId, "FindingId must not be null");
        Objects.requireNonNull(documentId, "DocumentId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(entityType, "EntityType must not be null");
        Objects.requireNonNull(confidence, "Confidence must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
        Objects.requireNonNull(occurredAt, "OccurredAt must not be null");
    }

    public static FindingDetectedEvent of(FindingId findingId, DocumentId documentId,
                                           TenantId tenantId, EntityType entityType,
                                           Confidence confidence, CorrelationId correlationId) {
        return new FindingDetectedEvent(findingId, documentId, tenantId, entityType,
                confidence, correlationId, Instant.now());
    }

    @Override
    public String eventType() {
        return "FindingDetected";
    }
}
