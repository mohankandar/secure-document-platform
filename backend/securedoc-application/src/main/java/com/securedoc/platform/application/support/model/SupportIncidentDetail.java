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
        String artifactAvailability,
        Instant technicalAttentionRetainedUntil,
        Instant artifactsPurgedAt,
        Map<String, String> processingFingerprint,
        Map<String, String> safeMetadata,
        Instant firstObservedAt,
        Instant lastObservedAt,
        Instant resolvedAt,
        List<Event> events,
        /** Task 0025: the LexaVault code (stored with the incident, else from the reason code). */
        String lvCode) {

    public SupportIncidentDetail {
        lvCode = lvCodeFor(lvCode != null ? lvCode : safeMetadata == null ? null : safeMetadata.get("lvCode"), reasonCode);
        processingFingerprint = processingFingerprint == null ? Map.of() : Map.copyOf(processingFingerprint);
        safeMetadata = safeMetadata == null ? Map.of() : Map.copyOf(safeMetadata);
        events = events == null ? List.of() : List.copyOf(events);
    }

    /** The code to show: a recorded LV code, else the code for the internal reason, else LV-SYS-001. */
    public static String lvCodeFor(String recorded, String reasonCode) {
        if (recorded != null && com.securedoc.platform.domain.error.LvErrorCode.fromCode(recorded).isPresent()) {
            return recorded.trim();
        }
        return com.securedoc.platform.application.error.LvErrorCodes.forDetailOrFallback(reasonCode, "incident").code();
    }

    public SupportIncidentDetail(UUID incidentId, UUID tenantId, UUID packageId, UUID documentId, String packageStatus,
                                 String primaryIdentifierLabel, String primaryIdentifierValue, String status,
                                 String businessOutcome, String businessImpact, boolean businessContinuityUsed,
                                 String stage, String component, String failureCategory, String reasonCode,
                                 boolean retryable, int attemptCount, UUID correlationId, String safeSummary,
                                 String artifactAvailability, Instant technicalAttentionRetainedUntil,
                                 Instant artifactsPurgedAt, Map<String, String> processingFingerprint,
                                 Map<String, String> safeMetadata, Instant firstObservedAt, Instant lastObservedAt,
                                 Instant resolvedAt, List<Event> events) {
        this(incidentId, tenantId, packageId, documentId, packageStatus, primaryIdentifierLabel, primaryIdentifierValue,
                status, businessOutcome, businessImpact, businessContinuityUsed, stage, component, failureCategory,
                reasonCode, retryable, attemptCount, correlationId, safeSummary, artifactAvailability,
                technicalAttentionRetainedUntil, artifactsPurgedAt, processingFingerprint, safeMetadata, firstObservedAt,
                lastObservedAt, resolvedAt, events, null);
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
