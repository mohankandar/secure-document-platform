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
        Instant resolvedAt,
        /** Task 0025: the LexaVault code (stored with the incident, else from the reason code). */
        String lvCode) {

    public SupportIncidentSummary {
        lvCode = com.securedoc.platform.application.support.model.SupportIncidentDetail.lvCodeFor(lvCode, reasonCode);
    }

    public SupportIncidentSummary(UUID incidentId, UUID tenantId, UUID packageId, UUID documentId, String packageStatus,
                                  String primaryIdentifierLabel, String primaryIdentifierValue, String stage,
                                  String component, String reasonCode, String status, String businessOutcome,
                                  boolean businessContinuityUsed, boolean retryable, int attemptCount,
                                  UUID correlationId, String businessImpact, Instant firstObservedAt,
                                  Instant lastObservedAt, Instant resolvedAt) {
        this(incidentId, tenantId, packageId, documentId, packageStatus, primaryIdentifierLabel, primaryIdentifierValue,
                stage, component, reasonCode, status, businessOutcome, businessContinuityUsed, retryable, attemptCount,
                correlationId, businessImpact, firstObservedAt, lastObservedAt, resolvedAt, null);
    }
}
