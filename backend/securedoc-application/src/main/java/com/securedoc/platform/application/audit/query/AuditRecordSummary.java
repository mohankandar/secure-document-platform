package com.securedoc.platform.application.audit.query;

import java.time.Instant;

/**
 * Safe audit record summary for API exposure. Contains only metadata — no raw sensitive values.
 * {@code actorDisplayName} and {@code caseId} are null for system work, for records not about a case, and for
 * records written before task 0016.
 */
public record AuditRecordSummary(
        String id,
        String actorId,
        String action,
        String resourceType,
        String resourceId,
        String outcome,
        String correlationId,
        String detail,
        Instant occurredAt,
        String actorDisplayName,
        String caseId
) {

    /** A summary with no display name and no case ID. */
    public AuditRecordSummary(String id, String actorId, String action, String resourceType, String resourceId,
                              String outcome, String correlationId, String detail, Instant occurredAt) {
        this(id, actorId, action, resourceType, resourceId, outcome, correlationId, detail, occurredAt, null, null);
    }
}
