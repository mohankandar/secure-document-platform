package com.securedoc.platform.application.technical.service;

import com.securedoc.platform.application.audit.query.AuditRecordFilter;
import com.securedoc.platform.application.audit.query.AuditRecordSummary;
import com.securedoc.platform.application.port.out.*;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.document.*;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.lifecycle.PackageLifecycle;
import com.securedoc.platform.domain.processing.*;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.FileName;
import com.securedoc.platform.domain.vo.MimeType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TechnicalExceptionQueryServiceStructuredIncidentTest {

    @Test
    void prefersDurableStructuredIncidentOverLegacyAuditParsing() {
        TenantId tenantId = TenantId.generate();
        ActorId actorId = ActorId.generate();
        CorrelationId correlationId = CorrelationId.generate();
        DocumentPackage pkg = DocumentPackage.create(tenantId, PackageType.MANUAL_UPLOAD, actorId, PackageMetadata.empty());
        Document document = Document.create(tenantId, pkg.id(), FileName.of("incident.pdf"), MimeType.pdf(), 10,
                Checksum.sha256("fixture"), DocumentType.PDF);
        pkg.addDocument(document); pkg.submit(); pkg.startProcessing();
        document.startExtraction();
        document.completeExtraction(List.of(Page.withText(1, 612, 792, "synthetic")));
        document.fail(); pkg.fail();

        Instant observed = Instant.now().minusSeconds(3);
        ProcessingIncident incident = ProcessingIncident.open(tenantId, pkg.id(), document.id(),
                ProcessingStage.AI_ANALYSIS, "AI", "DEPENDENCY_RESPONSE_ERROR", "AI_INVALID_RESPONSE",
                false, 2, correlationId, "Document processing failed",
                Map.of("aiProvider", "ollama", "aiModel", "qwen3.5:4b"), Map.of("scope", "DOCUMENT"), observed);
        ProcessingIncidentEvent failureEvent = ProcessingIncidentEvent.create(incident.id(), tenantId,
                ProcessingIncidentEventType.FAILURE_RECORDED, 2, "DOCUMENT", "AI_INVALID_RESPONSE", false,
                correlationId, Map.of("stage", "AI_ANALYSIS"), observed);
        FixedIncidentRepository incidents = new FixedIncidentRepository(incident, List.of(failureEvent));

        TechnicalExceptionQueryService service = new TechnicalExceptionQueryService(
                new FakePackageRepository(pkg), new FakeDocumentRepository(document), new EmptyLifecycleRepository(),
                new EmptyArtifactRepository(), new EmptyAuditQuery(), incidents);

        var detail = service.execute(pkg.id(), tenantId);
        assertEquals(incident.id(), detail.incidentId());
        assertEquals("OPEN", detail.incidentStatus());
        assertEquals("AI_ANALYSIS", detail.failedStage());
        assertEquals("AI_INVALID_RESPONSE", detail.failureCode());
        assertEquals("qwen3.5:4b", detail.processingFingerprint().get("aiModel"));
        assertEquals(1, detail.incidentEvents().size());
        assertEquals("FAILURE_RECORDED", detail.incidentEvents().get(0).eventType());
        assertFalse(detail.retryable(), "no retained original is available in this fixture");
    }

    private record FakePackageRepository(DocumentPackage pkg) implements PackageRepository {
        @Override public void save(DocumentPackage value) { }
        @Override public Optional<DocumentPackage> findByIdAndTenantId(PackageId id, TenantId tenantId) { return pkg.id().equals(id) && pkg.tenantId().equals(tenantId) ? Optional.of(pkg) : Optional.empty(); }
        @Override public List<DocumentPackage> findByTenantId(TenantId tenantId, int offset, int limit) { return List.of(pkg); }
        @Override public long countByTenantId(TenantId tenantId) { return 1; }
        @Override public Optional<DocumentPackage> findByTenantIdAndIntakeFingerprint(TenantId tenantId, String fingerprint) { return Optional.empty(); }
        @Override public List<DocumentPackage> findByStatus(PackageStatus status, int limit) { return List.of(); }
    }

    private record FakeDocumentRepository(Document document) implements DocumentRepository {
        @Override public void save(Document value) { }
        @Override public Optional<Document> findByIdAndTenantId(DocumentId id, TenantId tenantId) { return document.id().equals(id) && document.tenantId().equals(tenantId) ? Optional.of(document) : Optional.empty(); }
        @Override public List<Document> findByPackageIdAndTenantId(PackageId packageId, TenantId tenantId) { return document.packageId().equals(packageId) && document.tenantId().equals(tenantId) ? List.of(document) : List.of(); }
    }

    private static final class EmptyLifecycleRepository implements PackageLifecycleRepository {
        @Override public Optional<PackageLifecycle> findByPackageIdAndTenantId(PackageId packageId, TenantId tenantId) { return Optional.empty(); }
        @Override public void save(PackageLifecycle lifecycle) { }
        @Override public List<PackageLifecycle> findDueRedactedPurge(Instant now, int limit) { return List.of(); }
        @Override public List<PackageLifecycle> findDueFinalPurge(Instant now, int limit) { return List.of(); }
        @Override public List<PackageLifecycle> findPurgedCompletedExportsNeedingDashboardHide(int limit) { return List.of(); }
    }

    private static final class EmptyArtifactRepository implements ArtifactRepository {
        @Override public void save(Artifact artifact) { }
        @Override public Optional<Artifact> findByIdAndTenantId(ArtifactId id, TenantId tenantId) { return Optional.empty(); }
        @Override public List<Artifact> findByDocumentIdAndTenantId(DocumentId documentId, TenantId tenantId) { return List.of(); }
    }

    private static final class EmptyAuditQuery implements AuditQueryPort {
        @Override public List<AuditRecordSummary> findByFilter(AuditRecordFilter filter) { return List.of(); }
    }

    private static final class FixedIncidentRepository implements ProcessingIncidentRepository {
        private final ProcessingIncident incident;
        private final List<ProcessingIncidentEvent> events;
        private FixedIncidentRepository(ProcessingIncident incident, List<ProcessingIncidentEvent> events) {
            this.incident = incident; this.events = events;
        }
        @Override public void save(ProcessingIncident incident) { }
        @Override public Optional<ProcessingIncident> findByIdAndTenantId(UUID incidentId, TenantId tenantId) {
            return incident.id().equals(incidentId) && incident.tenantId().equals(tenantId) ? Optional.of(incident) : Optional.empty();
        }
        @Override public Optional<ProcessingIncident> findLatestByPackageIdAndTenantId(PackageId packageId, TenantId tenantId) { return Optional.of(incident); }
        @Override public List<ProcessingIncident> findRecentByPackageIdAndTenantId(PackageId packageId, TenantId tenantId, int limit) { return List.of(incident); }
        @Override public List<ProcessingIncident> findRecentByTenantId(TenantId tenantId, int limit) { return List.of(incident); }
        @Override public void saveEvent(ProcessingIncidentEvent event) { }
        @Override public List<ProcessingIncidentEvent> findEvents(UUID incidentId, TenantId tenantId, int limit) { return events; }
    }
}
