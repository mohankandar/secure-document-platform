package com.securedoc.platform.application.support.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Privacy-safe support case file built from the durable processing incident and event history. */
public record SupportIncidentDetail(
        UUID incidentId,
        UUID tenantId,
        UUID packageId,
        UUID documentId,
        String packageStatus,
        String primaryIdentifierLabel,
        String primaryIdentifierValue,
        String status,
        String businessOutcome,
        String businessImpact,
        boolean businessContinuityUsed,
        String stage,
        String component,
        String failureCategory,
        String reasonCode,
        boolean retryable,
        int attemptCount,
        UUID correlationId,
        String safeSummary,
        Map<String, String> processingFingerprint,
        Map<String, String> safeMetadata,
        Instant firstObservedAt,
        Instant lastObservedAt,
        Instant resolvedAt,
        List<Event> events) {

    public SupportIncidentDetail {
        processingFingerprint = processingFingerprint == null ? Map.of() : Map.copyOf(processingFingerprint);
        safeMetadata = safeMetadata == null ? Map.of() : Map.copyOf(safeMetadata);
        events = events == null ? List.of() : List.copyOf(events);
    }

    public record Event(
            String eventType,
            Integer attemptNumber,
            String scope,
            String reasonCode,
            Boolean retryable,
            UUID correlationId,
            Map<String, String> safeMetadata,
            Instant occurredAt) {
        public Event {
            safeMetadata = safeMetadata == null ? Map.of() : Map.copyOf(safeMetadata);
        }
    }
}
