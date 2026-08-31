package com.securedoc.platform.application.audit.query;

import java.time.Instant;

/**
 * Safe audit record summary for API exposure. Contains only metadata — no raw sensitive values.
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
        Instant occurredAt
) {
}
