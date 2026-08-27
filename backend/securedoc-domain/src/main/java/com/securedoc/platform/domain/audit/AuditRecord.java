package com.securedoc.platform.domain.audit;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable record of an auditable action in the system.
 * Captures who did what, to which resource, when, and whether it succeeded.
 */
public record AuditRecord(
        UUID id,
        TenantId tenantId,
        ActorId actorId,
        AuditAction action,
        String resourceType,
        String resourceId,
        AuditOutcome outcome,
        CorrelationId correlationId,
        String detail,
        Instant timestamp
) {

    public AuditRecord {
        Objects.requireNonNull(id, "AuditRecord id must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(actorId, "ActorId must not be null");
        Objects.requireNonNull(action, "Action must not be null");
        Objects.requireNonNull(resourceType, "Resource type must not be null");
        Objects.requireNonNull(resourceId, "Resource ID must not be null");
        Objects.requireNonNull(outcome, "Outcome must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
        Objects.requireNonNull(timestamp, "Timestamp must not be null");
    }

    public static AuditRecord create(TenantId tenantId, ActorId actorId, AuditAction action,
                                      String resourceType, String resourceId,
                                      AuditOutcome outcome, CorrelationId correlationId, String detail) {
        return new AuditRecord(UUID.randomUUID(), tenantId, actorId, action,
                resourceType, resourceId, outcome, correlationId, detail, Instant.now());
    }

    public Optional<String> detailOpt() {
        return Optional.ofNullable(detail);
    }
}
