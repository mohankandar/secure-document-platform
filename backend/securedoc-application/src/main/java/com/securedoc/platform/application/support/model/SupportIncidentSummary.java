package com.securedoc.platform.application.support.model;

import java.time.Instant;
import java.util.UUID;

/** Read-only support projection. Contains operational metadata only. */
public record SupportIncidentSummary(
        UUID incidentId,
        UUID tenantId,
        UUID packageId,
        UUID documentId,
        String packageStatus,
        String primaryIdentifierLabel,
        String primaryIdentifierValue,
        String stage,
        String component,
        String reasonCode,
        String status,
        String businessOutcome,
        boolean businessContinuityUsed,
        boolean retryable,
        int attemptCount,
        UUID correlationId,
        String businessImpact,
        Instant firstObservedAt,
        Instant lastObservedAt,
        Instant resolvedAt) {
}
