package com.securedoc.platform.domain.processing;

import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** One privacy-safe event in an incident's durable history. */
public record ProcessingIncidentEvent(
        UUID id,
        UUID incidentId,
        TenantId tenantId,
        ProcessingIncidentEventType eventType,
        Integer attemptNumber,
        String scope,
        String reasonCode,
        Boolean retryable,
        CorrelationId correlationId,
        Map<String, String> safeMetadata,
        Instant occurredAt
) {
    public ProcessingIncidentEvent {
        Objects.requireNonNull(id);
        Objects.requireNonNull(incidentId);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(eventType);
        Objects.requireNonNull(correlationId);
        Objects.requireNonNull(occurredAt);
        safeMetadata = safeMetadata == null ? Map.of() : Map.copyOf(safeMetadata);
    }

    public static ProcessingIncidentEvent create(UUID incidentId,
                                                 TenantId tenantId,
                                                 ProcessingIncidentEventType eventType,
                                                 Integer attemptNumber,
                                                 String scope,
                                                 String reasonCode,
                                                 Boolean retryable,
                                                 CorrelationId correlationId,
                                                 Map<String, String> safeMetadata,
                                                 Instant now) {
        return new ProcessingIncidentEvent(UUID.randomUUID(), incidentId, tenantId, eventType,
                attemptNumber, scope, reasonCode, retryable, correlationId, safeMetadata, now);
    }
}
