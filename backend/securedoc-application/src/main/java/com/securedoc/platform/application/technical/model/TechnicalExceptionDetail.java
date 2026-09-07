package com.securedoc.platform.application.technical.model;

import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Safe, tenant-scoped technical exception metadata. It intentionally contains no document values,
 * raw stack traces, credentials, tokens, prompts, OCR text, provider responses or protected values.
 */
public record TechnicalExceptionDetail(
        PackageId packageId,
        DocumentId documentId,
        String status,
        String failureCategory,
        String failedStage,
        String dependency,
        String failureCode,
        boolean retryable,
        int attempts,
        Instant failedAt,
        String correlationId,
        Instant originalRetainedUntil,
        String explanation,
        UUID incidentId,
        String incidentStatus,
        boolean businessContinuityUsed,
        String businessOutcome,
        Map<String, String> processingFingerprint,
        List<IncidentEventSummary> incidentEvents
) {
    public TechnicalExceptionDetail {
        processingFingerprint = processingFingerprint == null ? Map.of() : Map.copyOf(processingFingerprint);
        incidentEvents = incidentEvents == null ? List.of() : List.copyOf(incidentEvents);
    }

    /** Compatibility constructor for existing focused tests and legacy audit-backed incidents. */
    public TechnicalExceptionDetail(PackageId packageId,
                                    DocumentId documentId,
                                    String status,
                                    String failureCategory,
                                    String failedStage,
                                    String dependency,
                                    String failureCode,
                                    boolean retryable,
                                    int attempts,
                                    Instant failedAt,
                                    String correlationId,
                                    Instant originalRetainedUntil,
                                    String explanation) {
        this(packageId, documentId, status, failureCategory, failedStage, dependency, failureCode,
                retryable, attempts, failedAt, correlationId, originalRetainedUntil, explanation,
                null, "LEGACY_AUDIT", false, null, Map.of(), List.of());
    }

    public record IncidentEventSummary(
            String eventType,
            Integer attemptNumber,
            String scope,
            String reasonCode,
            Boolean retryable,
            String correlationId,
            Instant occurredAt,
            Map<String, String> safeMetadata) {
        public IncidentEventSummary {
            safeMetadata = safeMetadata == null ? Map.of() : Map.copyOf(safeMetadata);
        }
    }
}
