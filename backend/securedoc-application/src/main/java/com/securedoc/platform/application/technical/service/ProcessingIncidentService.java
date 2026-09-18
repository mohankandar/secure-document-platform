package com.securedoc.platform.application.technical.service;

import com.securedoc.platform.application.port.out.ProcessingFingerprintProvider;
import com.securedoc.platform.application.port.out.ProcessingIncidentRepository;
import com.securedoc.platform.application.resilience.TechnicalFailureClassifier;
import com.securedoc.platform.application.observability.StructuredLogContext;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.processing.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records and advances privacy-safe structured processing incidents.
 *
 * <p>This service is deliberately provider/stage neutral. AI is only one current producer;
 * OCR, routing, delivery and future document-profile processors use the same incident model.
 */
public final class ProcessingIncidentService {
    private static final Logger log = LoggerFactory.getLogger(ProcessingIncidentService.class);
    private final ProcessingIncidentRepository repository;
    private final ProcessingFingerprintProvider fingerprintProvider;

    public ProcessingIncidentService(ProcessingIncidentRepository repository,
                                     ProcessingFingerprintProvider fingerprintProvider) {
        this.repository = Objects.requireNonNull(repository);
        this.fingerprintProvider = Objects.requireNonNull(fingerprintProvider);
    }

    public static ProcessingIncidentService noop() {
        return new ProcessingIncidentService(ProcessingIncidentRepository.none(), ProcessingFingerprintProvider.empty());
    }

    public ProcessingIncident recordFailure(TenantId tenantId,
                                            PackageId packageId,
                                            DocumentId documentId,
                                            ProcessingStage preferredStage,
                                            Throwable failure,
                                            CorrelationId correlationId,
                                            String safeSummary,
                                            Map<String, String> safeMetadata) {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(packageId);
        Objects.requireNonNull(failure);
        Objects.requireNonNull(correlationId);
        var classification = TechnicalFailureClassifier.classify(failure);
        ProcessingStage stage = TechnicalFailureTaxonomy.stage(
                classification.dependency(), classification.failureCode(), preferredStage);
        String component = classification.dependency().name();
        String reasonCode = classification.failureCode().name();
        String category = TechnicalFailureTaxonomy.category(reasonCode, component);
        Instant now = Instant.now();

        Map<String, String> metadata = new LinkedHashMap<>();
        if (safeMetadata != null) metadata.putAll(safeMetadata);
        metadata.putIfAbsent("scope", documentId == null ? "PACKAGE" : "DOCUMENT");
        metadata.putIfAbsent("preferredStage", preferredStage == null ? ProcessingStage.UNKNOWN.name() : preferredStage.name());

        ProcessingIncident incident = ProcessingIncident.open(
                tenantId, packageId, documentId, stage, component, category, reasonCode,
                classification.retryable(), classification.attempts(), correlationId,
                sanitizeSummary(safeSummary), sanitizeMap(fingerprintProvider.currentFingerprint(tenantId, packageId)),
                sanitizeMap(metadata), now);
        try {
            repository.save(incident);
            repository.saveEvent(ProcessingIncidentEvent.create(
                    incident.id(), tenantId, ProcessingIncidentEventType.FAILURE_RECORDED,
                    classification.attempts(), documentId == null ? "PACKAGE" : "DOCUMENT",
                    reasonCode, classification.retryable(), correlationId,
                    Map.of("stage", stage.name(), "component", component), now));
            StructuredLogContext.warn(log, "PROCESSING_INCIDENT_RECORDED", Map.of(
                    "tenantId", tenantId, "packageId", packageId, "incidentId", incident.id(),
                    "stage", stage, "component", component, "failureCategory", category,
                    "reasonCode", reasonCode, "attempt", classification.attempts(),
                    "retryable", classification.retryable(), "status", incident.status()),
                    "Structured processing incident recorded");
        } catch (RuntimeException ignored) {
            // Incident persistence is support telemetry and must never mask the original processing failure.
        }
        return incident;
    }

    public void markRetryScheduled(PackageId packageId, TenantId tenantId, CorrelationId correlationId) {
        safelyAdvance(packageId, tenantId, incident -> {
            Instant now = Instant.now();
            repository.save(incident.retryScheduled(now));
            repository.saveEvent(ProcessingIncidentEvent.create(
                    incident.id(), tenantId, ProcessingIncidentEventType.RETRY_SCHEDULED,
                    null, "PACKAGE", incident.reasonCode(), incident.retryable(), correlationId,
                    Map.of(), now));
            StructuredLogContext.info(log, "PROCESSING_INCIDENT_RETRY_SCHEDULED", Map.of(
                    "tenantId", tenantId, "packageId", packageId, "incidentId", incident.id(),
                    "reasonCode", incident.reasonCode(), "status", ProcessingIncidentStatus.RETRY_SCHEDULED),
                    "Processing incident retry scheduled");
        });
    }

    public void markBusinessContinuityStarted(PackageId packageId, TenantId tenantId, CorrelationId correlationId) {
        safelyAdvance(packageId, tenantId, incident -> {
            Instant now = Instant.now();
            repository.save(incident.businessContinuityStarted(now));
            repository.saveEvent(ProcessingIncidentEvent.create(
                    incident.id(), tenantId, ProcessingIncidentEventType.BUSINESS_CONTINUITY_STARTED,
                    null, "BUSINESS", incident.reasonCode(), null, correlationId,
                    Map.of("businessOutcome", "IN_PROGRESS"), now));
            StructuredLogContext.info(log, "BUSINESS_CONTINUITY_STARTED", Map.of(
                    "tenantId", tenantId, "packageId", packageId, "incidentId", incident.id(),
                    "reasonCode", incident.reasonCode(), "status", ProcessingIncidentStatus.BUSINESS_CONTINUED,
                    "businessOutcome", "IN_PROGRESS"), "Business continuity started");
        });
    }

    public void markBusinessContinuitySubmitted(PackageId packageId, TenantId tenantId, CorrelationId correlationId) {
        safelyAdvance(packageId, tenantId, incident -> {
            Instant now = Instant.now();
            repository.save(incident.businessContinuitySubmitted(now));
            repository.saveEvent(ProcessingIncidentEvent.create(
                    incident.id(), tenantId, ProcessingIncidentEventType.BUSINESS_CONTINUITY_SUBMITTED,
                    null, "BUSINESS", incident.reasonCode(), null, correlationId,
                    Map.of("businessOutcome", "SUBMITTED"), now));
            StructuredLogContext.info(log, "BUSINESS_CONTINUITY_SUBMITTED", Map.of(
                    "tenantId", tenantId, "packageId", packageId, "incidentId", incident.id(),
                    "reasonCode", incident.reasonCode(), "status", ProcessingIncidentStatus.BUSINESS_CONTINUED,
                    "businessOutcome", "SUBMITTED"), "Business continuity submitted");
        });
    }

    /**
     * Marks an automatic recovery resolved, but deliberately leaves business-continuity incidents
     * open for IT investigation even after business delivery succeeds.
     */
    public void markProcessingCompleted(PackageId packageId, TenantId tenantId, CorrelationId correlationId) {
        try {
            Instant now = Instant.now();
            var incidents = repository.findRecentByPackageIdAndTenantId(packageId, tenantId, 20);
            for (ProcessingIncident incident : incidents) {
                if (incident.businessContinuityUsed() && !"COMPLETED".equals(incident.businessOutcome())) {
                    repository.save(incident.businessCompleted(now));
                    repository.saveEvent(ProcessingIncidentEvent.create(
                            incident.id(), tenantId, ProcessingIncidentEventType.BUSINESS_COMPLETED,
                            null, "BUSINESS", incident.reasonCode(), null, correlationId,
                            Map.of("businessOutcome", "COMPLETED"), now));
                    StructuredLogContext.info(log, "BUSINESS_CONTINUITY_COMPLETED", Map.of(
                            "tenantId", tenantId, "packageId", packageId, "incidentId", incident.id(),
                            "reasonCode", incident.reasonCode(), "status", ProcessingIncidentStatus.BUSINESS_CONTINUED,
                            "businessOutcome", "COMPLETED"), "Business continuity completed; technical incident remains open");
                }
            }
            incidents.stream()
                    .filter(incident -> !incident.businessContinuityUsed()
                            && incident.status() == ProcessingIncidentStatus.RETRY_SCHEDULED)
                    .findFirst()
                    .ifPresent(incident -> {
                        repository.save(incident.resolved(now));
                        repository.saveEvent(ProcessingIncidentEvent.create(
                                incident.id(), tenantId, ProcessingIncidentEventType.RESOLVED,
                                null, "AUTOMATIC_RECOVERY", incident.reasonCode(), null, correlationId,
                                Map.of("resolution", "AUTOMATIC_RETRY_SUCCEEDED"), now));
                        StructuredLogContext.info(log, "PROCESSING_INCIDENT_RESOLVED", Map.of(
                                "tenantId", tenantId, "packageId", packageId, "incidentId", incident.id(),
                                "reasonCode", incident.reasonCode(), "status", ProcessingIncidentStatus.RESOLVED,
                                "decision", "AUTOMATIC_RETRY_SUCCEEDED"), "Processing incident resolved after automatic recovery");
                    });
        } catch (RuntimeException ignored) {
            // Support telemetry must never block successful business completion.
        }
    }

    private void safelyAdvance(PackageId packageId, TenantId tenantId, java.util.function.Consumer<ProcessingIncident> transition) {
        try {
            repository.findLatestByPackageIdAndTenantId(packageId, tenantId).ifPresent(transition);
        } catch (RuntimeException ignored) {
            // Support telemetry must never block retry, business continuity, redaction or delivery.
        }
    }

    private String sanitizeSummary(String value) {
        if (value == null || value.isBlank()) return "Technical processing failure";
        String trimmed = value.trim();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500);
    }

    private Map<String, String> sanitizeMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) return Map.of();
        Map<String, String> safe = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null || value.isBlank()) return;
            String safeKey = key.trim();
            String normalizedKey = safeKey.toLowerCase(java.util.Locale.ROOT);
            if (normalizedKey.contains("prompt") || normalizedKey.contains("response")
                    || normalizedKey.contains("ocrtext") || normalizedKey.contains("documenttext")
                    || normalizedKey.contains("matchedtext") || normalizedKey.contains("content")
                    || normalizedKey.contains("ssn") || normalizedKey.contains("pan")
                    || normalizedKey.contains("dateofbirth") || normalizedKey.contains("dob")
                    || normalizedKey.contains("creditscore")) {
                return;
            }
            String safeValue = value.trim();
            if (safeKey.length() > 80) safeKey = safeKey.substring(0, 80);
            if (safeValue.length() > 200) safeValue = safeValue.substring(0, 200);
            safe.put(safeKey, safeValue);
        });
        return Map.copyOf(safe);
    }
}
