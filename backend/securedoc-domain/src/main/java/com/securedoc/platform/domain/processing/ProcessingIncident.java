package com.securedoc.platform.domain.processing;

import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable, privacy-safe technical incident.
 *
 * <p>Document content, OCR text, prompts, provider responses and protected values must never be
 * placed in safeSummary, processingFingerprint or safeMetadata.
 */
public record ProcessingIncident(
        UUID id,
        TenantId tenantId,
        PackageId packageId,
        DocumentId documentId,
        ProcessingStage stage,
        String component,
        String failureCategory,
        String reasonCode,
        boolean retryable,
        int attempts,
        CorrelationId correlationId,
        ProcessingIncidentStatus status,
        boolean businessContinuityUsed,
        String businessOutcome,
        String safeSummary,
        Map<String, String> processingFingerprint,
        Map<String, String> safeMetadata,
        Instant firstObservedAt,
        Instant lastObservedAt,
        Instant resolvedAt
) {
    public ProcessingIncident {
        Objects.requireNonNull(id, "Incident id must not be null");
        Objects.requireNonNull(tenantId, "Tenant id must not be null");
        Objects.requireNonNull(packageId, "Package id must not be null");
        Objects.requireNonNull(stage, "Processing stage must not be null");
        Objects.requireNonNull(component, "Component must not be null");
        Objects.requireNonNull(failureCategory, "Failure category must not be null");
        Objects.requireNonNull(reasonCode, "Reason code must not be null");
        Objects.requireNonNull(correlationId, "Correlation id must not be null");
        Objects.requireNonNull(status, "Incident status must not be null");
        Objects.requireNonNull(safeSummary, "Safe summary must not be null");
        Objects.requireNonNull(firstObservedAt, "First observed time must not be null");
        Objects.requireNonNull(lastObservedAt, "Last observed time must not be null");
        attempts = Math.max(1, attempts);
        processingFingerprint = processingFingerprint == null ? Map.of() : Map.copyOf(processingFingerprint);
        safeMetadata = safeMetadata == null ? Map.of() : Map.copyOf(safeMetadata);
    }

    public static ProcessingIncident open(TenantId tenantId,
                                          PackageId packageId,
                                          DocumentId documentId,
                                          ProcessingStage stage,
                                          String component,
                                          String failureCategory,
                                          String reasonCode,
                                          boolean retryable,
                                          int attempts,
                                          CorrelationId correlationId,
                                          String safeSummary,
                                          Map<String, String> processingFingerprint,
                                          Map<String, String> safeMetadata,
                                          Instant now) {
        return new ProcessingIncident(UUID.randomUUID(), tenantId, packageId, documentId, stage,
                component, failureCategory, reasonCode, retryable, attempts, correlationId,
                ProcessingIncidentStatus.OPEN, false, null, safeSummary, processingFingerprint,
                safeMetadata, now, now, null);
    }

    public ProcessingIncident retryScheduled(Instant now) {
        return copy(ProcessingIncidentStatus.RETRY_SCHEDULED, businessContinuityUsed, businessOutcome, now, resolvedAt);
    }

    public ProcessingIncident investigating(Instant now) {
        return copy(ProcessingIncidentStatus.INVESTIGATING, businessContinuityUsed, businessOutcome, now, null);
    }

    public ProcessingIncident resolvedWithOutcome(String outcome, Instant now) {
        return copy(ProcessingIncidentStatus.RESOLVED, businessContinuityUsed, outcome, now, now);
    }

    public ProcessingIncident autoClosed(String outcome, Instant now) {
        return copy(ProcessingIncidentStatus.AUTO_CLOSED, businessContinuityUsed, outcome, now, now);
    }

    public ProcessingIncident businessContinuityStarted(Instant now) {
        return copy(ProcessingIncidentStatus.BUSINESS_CONTINUED, true, "IN_PROGRESS", now, null);
    }

    public ProcessingIncident businessContinuitySubmitted(Instant now) {
        return copy(ProcessingIncidentStatus.BUSINESS_CONTINUED, true, "SUBMITTED", now, null);
    }

    public ProcessingIncident businessCompleted(Instant now) {
        return copy(ProcessingIncidentStatus.BUSINESS_CONTINUED, true, "COMPLETED", now, null);
    }

    public ProcessingIncident resolved(Instant now) {
        return copy(ProcessingIncidentStatus.RESOLVED, businessContinuityUsed, businessOutcome, now, now);
    }

    private ProcessingIncident copy(ProcessingIncidentStatus newStatus,
                                    boolean continuityUsed,
                                    String outcome,
                                    Instant now,
                                    Instant newResolvedAt) {
        return new ProcessingIncident(id, tenantId, packageId, documentId, stage, component,
                failureCategory, reasonCode, retryable, attempts, correlationId, newStatus,
                continuityUsed, outcome, safeSummary, processingFingerprint, safeMetadata,
                firstObservedAt, now, newResolvedAt);
    }
}
