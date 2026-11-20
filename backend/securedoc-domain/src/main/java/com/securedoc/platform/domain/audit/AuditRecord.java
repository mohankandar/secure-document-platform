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
 *
 * <p>{@code actorDisplayName} and {@code caseId} are recorded at the time of the action (task 0016). Both may be
 * null: work done by the system has no display name, and records not about a case have no case ID. Records written
 * before task 0016 have neither.
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
        Instant timestamp,
        String actorDisplayName,
        String caseId
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
        actorDisplayName = blankToNull(actorDisplayName);
        caseId = blankToNull(caseId);
    }

    public static AuditRecord create(TenantId tenantId, ActorId actorId, AuditAction action,
                                      String resourceType, String resourceId,
                                      AuditOutcome outcome, CorrelationId correlationId, String detail) {
        return create(tenantId, actorId, action, resourceType, resourceId, outcome, correlationId, detail, null, null);
    }

    public static AuditRecord create(TenantId tenantId, ActorId actorId, AuditAction action,
                                      String resourceType, String resourceId,
                                      AuditOutcome outcome, CorrelationId correlationId, String detail,
                                      String actorDisplayName, String caseId) {
        return new AuditRecord(UUID.randomUUID(), tenantId, actorId, action,
                resourceType, resourceId, outcome, correlationId, detail, Instant.now(), actorDisplayName, caseId);
    }

    public Optional<String> detailOpt() {
        return Optional.ofNullable(detail);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
