package com.securedoc.platform.application.technical.service;

import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.port.out.ProcessingIncidentRepository;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.processing.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProcessingIncidentServiceTest {

    @Test
    void recordsAiFailureAsGenericStructuredIncidentWithSafeFingerprintAndHistory() {
        InMemoryRepository repository = new InMemoryRepository();
        ProcessingIncidentService service = new ProcessingIncidentService(repository,
                (tenantId, packageId) -> Map.of("applicationVersion", "0.1.0", "aiProvider", "ollama", "aiModel", "qwen3.5:4b"));
        TenantId tenantId = TenantId.generate();
        PackageId packageId = PackageId.generate();
        DocumentId documentId = DocumentId.generate();
        CorrelationId correlationId = CorrelationId.generate();
        TechnicalDependencyException failure = new TechnicalDependencyException(
                TechnicalDependency.AI, TechnicalFailureCode.AI_INVALID_RESPONSE, true,
                "Provider response could not be safely used").withAttempts(2);

        ProcessingIncident incident = service.recordFailure(tenantId, packageId, documentId,
                ProcessingStage.SENSITIVE_DETECTION, failure, correlationId,
                "Document processing failed", Map.of("batchScope", "DOCUMENT"));

        assertEquals(ProcessingStage.AI_ANALYSIS, incident.stage());
        assertEquals("AI", incident.component());
        assertEquals("AI_INVALID_RESPONSE", incident.reasonCode());
        assertEquals("DEPENDENCY_RESPONSE_ERROR", incident.failureCategory());
        assertTrue(incident.retryable());
        assertEquals(2, incident.attempts());
        assertEquals("qwen3.5:4b", incident.processingFingerprint().get("aiModel"));
        assertFalse(incident.safeMetadata().toString().contains("123-45"));
        assertEquals(1, repository.events.size());
        assertEquals(ProcessingIncidentEventType.FAILURE_RECORDED, repository.events.get(0).eventType());
        assertEquals(2, repository.events.get(0).attemptNumber());
    }

    @Test
    void dropsUnsafeMetadataKeysBeforePersistence() {
        InMemoryRepository repository = new InMemoryRepository();
        ProcessingIncidentService service = new ProcessingIncidentService(repository,
                (tenantId, packageId) -> Map.of("aiModel", "qwen3.5:4b", "rawPrompt", "must never persist"));
        TenantId tenantId = TenantId.generate();
        PackageId packageId = PackageId.generate();
        ProcessingIncident incident = service.recordFailure(tenantId, packageId, DocumentId.generate(),
                ProcessingStage.SENSITIVE_DETECTION,
                new TechnicalDependencyException(TechnicalDependency.AI, TechnicalFailureCode.AI_INVALID_RESPONSE,
                        false, "Invalid response"), CorrelationId.generate(), "Document processing failed",
                Map.of("pageCount", "4", "ocrText", "sensitive body text", "matchedText", "protected value"));

        assertEquals("4", incident.safeMetadata().get("pageCount"));
        assertFalse(incident.safeMetadata().containsKey("ocrText"));
        assertFalse(incident.safeMetadata().containsKey("matchedText"));
        assertFalse(incident.processingFingerprint().containsKey("rawPrompt"));
    }

    @Test
    void businessCompletionDoesNotResolveTechnicalIncident() {
        InMemoryRepository repository = new InMemoryRepository();
        ProcessingIncidentService service = new ProcessingIncidentService(repository, (tenantId, packageId) -> Map.of());
        TenantId tenantId = TenantId.generate();
        PackageId packageId = PackageId.generate();
        CorrelationId failureCorrelation = CorrelationId.generate();
        service.recordFailure(tenantId, packageId, DocumentId.generate(), ProcessingStage.SENSITIVE_DETECTION,
                new TechnicalDependencyException(TechnicalDependency.AI, TechnicalFailureCode.AI_INVALID_RESPONSE,
                        false, "Invalid response"), failureCorrelation, "Document processing failed", Map.of());

        service.markBusinessContinuityStarted(packageId, tenantId, CorrelationId.generate());
        service.markBusinessContinuitySubmitted(packageId, tenantId, CorrelationId.generate());
        service.markProcessingCompleted(packageId, tenantId, CorrelationId.generate());

        ProcessingIncident incident = repository.latest.orElseThrow();
        assertEquals(ProcessingIncidentStatus.BUSINESS_CONTINUED, incident.status());
        assertTrue(incident.businessContinuityUsed());
        assertEquals("COMPLETED", incident.businessOutcome());
        assertNull(incident.resolvedAt(), "IT incident must remain unresolved after business completes manually");
        assertTrue(repository.events.stream().anyMatch(e -> e.eventType() == ProcessingIncidentEventType.BUSINESS_COMPLETED));
        assertFalse(repository.events.stream().anyMatch(e -> e.eventType() == ProcessingIncidentEventType.RESOLVED));
    }

    @Test
    void successfulTechnicalRetryResolvesIncidentWhenBusinessFallbackWasNotUsed() {
        InMemoryRepository repository = new InMemoryRepository();
        ProcessingIncidentService service = new ProcessingIncidentService(repository, (tenantId, packageId) -> Map.of());
        TenantId tenantId = TenantId.generate();
        PackageId packageId = PackageId.generate();
        service.recordFailure(tenantId, packageId, DocumentId.generate(), ProcessingStage.TEXT_EXTRACTION,
                new TechnicalDependencyException(TechnicalDependency.OCR, TechnicalFailureCode.OCR_TIMEOUT,
                        true, "OCR timeout"), CorrelationId.generate(), "Extraction failed", Map.of());

        service.markRetryScheduled(packageId, tenantId, CorrelationId.generate());
        service.markProcessingCompleted(packageId, tenantId, CorrelationId.generate());

        ProcessingIncident incident = repository.latest.orElseThrow();
        assertEquals(ProcessingIncidentStatus.RESOLVED, incident.status());
        assertNotNull(incident.resolvedAt());
        assertTrue(repository.events.stream().anyMatch(e -> e.eventType() == ProcessingIncidentEventType.RETRY_SCHEDULED));
        assertTrue(repository.events.stream().anyMatch(e -> e.eventType() == ProcessingIncidentEventType.RESOLVED));
    }

    @Test
    void retentionAutoCloseClosesAllActiveIncidentsForThePackageButKeepsHistory() {
        MultiIncidentRepository repository = new MultiIncidentRepository();
        ProcessingIncidentService service = new ProcessingIncidentService(repository, (tenantId, packageId) -> Map.of());
        TenantId tenantId = TenantId.generate();
        PackageId packageId = PackageId.generate();
        java.time.Instant now = java.time.Instant.now();

        ProcessingIncident first = ProcessingIncident.open(tenantId, packageId, null,
                ProcessingStage.TEXT_EXTRACTION, "OCR", "TIMEOUT", "OCR_TIMEOUT", true, 2,
                CorrelationId.generate(), "Extraction failed", Map.of(), Map.of(), now.minusSeconds(60));
        ProcessingIncident second = ProcessingIncident.open(tenantId, packageId, null,
                ProcessingStage.DOCUMENT_PROCESSING, "PACKAGE_PROCESSING_COORDINATION",
                "RECOVERY_EXHAUSTED", "PROCESSING_RECOVERY_EXHAUSTED", false, 3,
                CorrelationId.generate(), "Recovery exhausted", Map.of(), Map.of(), now.minusSeconds(30));
        repository.values.add(first);
        repository.values.add(second);

        service.markTechnicalAttentionAutoClosed(packageId, tenantId, CorrelationId.generate());

        assertEquals(2, repository.values.size(), "incident rows are durable history and must not be deleted");
        assertTrue(repository.values.stream().allMatch(i -> i.status() == ProcessingIncidentStatus.AUTO_CLOSED));
        assertTrue(repository.values.stream().allMatch(i -> "RETENTION_EXPIRED".equals(i.businessOutcome())));
        assertEquals(2, repository.events.stream()
                .filter(e -> e.eventType() == ProcessingIncidentEventType.AUTO_CLOSED_BY_RETENTION).count());
    }

    private static final class MultiIncidentRepository implements ProcessingIncidentRepository {
        final List<ProcessingIncident> values = new ArrayList<>();
        final List<ProcessingIncidentEvent> events = new ArrayList<>();

        @Override public void save(ProcessingIncident incident) {
            for (int i = 0; i < values.size(); i++) {
                if (values.get(i).id().equals(incident.id())) {
                    values.set(i, incident);
                    return;
                }
            }
            values.add(incident);
        }
        @Override public Optional<ProcessingIncident> findByIdAndTenantId(UUID incidentId, TenantId tenantId) {
            return values.stream().filter(i -> i.id().equals(incidentId) && i.tenantId().equals(tenantId)).findFirst();
        }
        @Override public Optional<ProcessingIncident> findLatestByPackageIdAndTenantId(PackageId packageId, TenantId tenantId) {
            return findRecentByPackageIdAndTenantId(packageId, tenantId, 1).stream().findFirst();
        }
        @Override public List<ProcessingIncident> findRecentByPackageIdAndTenantId(PackageId packageId, TenantId tenantId, int limit) {
            return values.stream()
                    .filter(i -> i.packageId().equals(packageId) && i.tenantId().equals(tenantId))
                    .sorted(java.util.Comparator.comparing(ProcessingIncident::lastObservedAt).reversed())
                    .limit(limit).toList();
        }
        @Override public List<ProcessingIncident> findRecentByTenantId(TenantId tenantId, int limit) {
            return values.stream().filter(i -> i.tenantId().equals(tenantId))
                    .sorted(java.util.Comparator.comparing(ProcessingIncident::lastObservedAt).reversed())
                    .limit(limit).toList();
        }
        @Override public void saveEvent(ProcessingIncidentEvent event) { events.add(event); }
        @Override public List<ProcessingIncidentEvent> findEvents(UUID incidentId, TenantId tenantId, int limit) {
            return events.stream().filter(e -> e.incidentId().equals(incidentId) && e.tenantId().equals(tenantId))
                    .limit(limit).toList();
        }
    }

    // ---- Task 0023 C3 (#367): a failed incident save is logged with type + code, never content ----

    @Test
    void failedIncidentSaveIsLoggedWithTypeAndCodeButNoMessage() {
        InMemoryRepository failing = new InMemoryRepository() {
            @Override public void save(ProcessingIncident incident) {
                throw new IllegalStateException("insert failed for value 123-45-6789");
            }
            @Override public Optional<ProcessingIncident> findLatestByPackageIdAndTenantId(PackageId p, TenantId t) {
                throw new IllegalStateException("select failed for value 123-45-6789");
            }
            @Override public List<ProcessingIncident> findRecentByPackageIdAndTenantId(PackageId p, TenantId t, int limit) {
                throw new IllegalStateException("select failed for value 123-45-6789");
            }
        };
        ProcessingIncidentService service = new ProcessingIncidentService(failing, (t, p) -> Map.of());
        TenantId tenantId = TenantId.generate();
        PackageId packageId = PackageId.generate();
        var failure = new TechnicalDependencyException(TechnicalDependency.OCR, TechnicalFailureCode.OCR_TIMEOUT, true, "x");

        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(ProcessingIncidentService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertDoesNotThrow(() -> service.recordFailure(tenantId, packageId, null, ProcessingStage.OCR, failure,
                    CorrelationId.generate(), "Document processing failed", Map.of()));
            service.recordRecoveryExhausted(tenantId, packageId, 3, 3, CorrelationId.generate());
            service.markRetryScheduled(packageId, tenantId, CorrelationId.generate());
            service.resolveNoReprocess(packageId, tenantId, CorrelationId.generate(), null, false);
            service.markProcessingCompleted(packageId, tenantId, CorrelationId.generate());
        } finally {
            logger.detachAppender(appender);
        }

        List<String> lines = appender.list.stream()
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .filter(line -> line.startsWith("PROCESSING_INCIDENT_SAVE_FAILED"))
                .toList();
        assertEquals(5, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("operation=RECORD_FAILURE"));
        assertTrue(lines.get(0).contains("incidentReasonCode=OCR_TIMEOUT"));
        assertTrue(lines.get(0).contains("failureType=IllegalStateException"));
        assertTrue(lines.get(0).contains("package=" + packageId));
        assertTrue(lines.get(1).contains("incidentReasonCode=PROCESSING_RECOVERY_EXHAUSTED"));
        assertTrue(lines.get(2).contains("operation=ADVANCE_LATEST"));
        assertTrue(lines.get(3).contains("operation=ADVANCE_ALL_ACTIVE"));
        assertTrue(lines.get(4).contains("operation=MARK_PROCESSING_COMPLETED"));
        assertTrue(appender.list.stream().noneMatch(e -> e.getFormattedMessage().contains("123-45-6789")
                || e.getThrowableProxy() != null), "No exception message or stack may be logged");
    }

    @Test
    void newDispatchAndRedactionCodesHaveTheirOwnCategoryAndStage() {
        InMemoryRepository repository = new InMemoryRepository();
        ProcessingIncidentService service = new ProcessingIncidentService(repository, (t, p) -> Map.of());
        var verification = new TechnicalDependencyException(TechnicalDependency.REDACTION,
                TechnicalFailureCode.REDACTION_TEXT_LAYER_REMAINS, false, "x");
        ProcessingIncident incident = service.recordFailure(TenantId.generate(), PackageId.generate(),
                DocumentId.generate(), ProcessingStage.REDACTION, verification, CorrelationId.generate(),
                "Document redaction failed", Map.of());
        assertEquals("REDACTION_TEXT_LAYER_REMAINS", incident.reasonCode());
        assertEquals("REDACTION_SAFETY", incident.failureCategory(), "REDACTION contains IO; must not become REDACTION_FAILURE");
        assertEquals(ProcessingStage.VERIFICATION, incident.stage());

        var location = new TechnicalDependencyException(TechnicalDependency.REDACTION,
                TechnicalFailureCode.REDACTION_LOCATION_MISSING, false, "x");
        ProcessingIncident locationIncident = service.recordFailure(TenantId.generate(), PackageId.generate(),
                DocumentId.generate(), ProcessingStage.REDACTION, location, CorrelationId.generate(),
                "Document redaction failed", Map.of());
        assertEquals(ProcessingStage.REDACTION, locationIncident.stage());
    }

    private static class InMemoryRepository implements ProcessingIncidentRepository {
        Optional<ProcessingIncident> latest = Optional.empty();
        final List<ProcessingIncidentEvent> events = new ArrayList<>();

        @Override public void save(ProcessingIncident incident) { latest = Optional.of(incident); }
        @Override public Optional<ProcessingIncident> findByIdAndTenantId(UUID incidentId, TenantId tenantId) {
            return latest.filter(i -> i.id().equals(incidentId) && i.tenantId().equals(tenantId));
        }
        @Override public Optional<ProcessingIncident> findLatestByPackageIdAndTenantId(PackageId packageId, TenantId tenantId) {
            return latest.filter(i -> i.packageId().equals(packageId) && i.tenantId().equals(tenantId));
        }
        @Override public List<ProcessingIncident> findRecentByPackageIdAndTenantId(PackageId packageId, TenantId tenantId, int limit) {
            return findLatestByPackageIdAndTenantId(packageId, tenantId).stream().toList();
        }
        @Override public List<ProcessingIncident> findRecentByTenantId(TenantId tenantId, int limit) {
            return latest.filter(i -> i.tenantId().equals(tenantId)).stream().toList();
        }
        @Override public void saveEvent(ProcessingIncidentEvent event) { events.add(event); }
        @Override public List<ProcessingIncidentEvent> findEvents(UUID incidentId, TenantId tenantId, int limit) {
            return events.stream().filter(e -> e.incidentId().equals(incidentId) && e.tenantId().equals(tenantId)).limit(limit).toList();
        }
    }
}
