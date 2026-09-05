package com.securedoc.platform.application.technical.service;

import com.securedoc.platform.application.audit.query.AuditRecordFilter;
import com.securedoc.platform.application.audit.query.AuditRecordSummary;
import com.securedoc.platform.application.port.out.*;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.document.*;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.lifecycle.PackageLifecycle;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.FileName;
import com.securedoc.platform.domain.vo.MimeType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TechnicalExceptionQueryServiceManualFallbackTest {
    @Test
    void preservesOriginalAiFailureClassificationAfterBusinessLeavesFailedState() {
        TenantId tenantId = TenantId.generate();
        ActorId actorId = ActorId.generate();
        CorrelationId correlationId = CorrelationId.generate();
        DocumentPackage pkg = DocumentPackage.create(tenantId, PackageType.MANUAL_UPLOAD, actorId, PackageMetadata.empty());
        Document document = Document.create(tenantId, pkg.id(), FileName.of("failed.pdf"), MimeType.pdf(), 10,
                Checksum.sha256("fixture"), DocumentType.PDF);
        pkg.addDocument(document); pkg.submit(); pkg.startProcessing();
        document.startExtraction();
        document.completeExtraction(List.of(Page.withText(1, 612, 792, "synthetic")));
        document.fail(); pkg.fail();
        document.prepareForManualFallback(); pkg.prepareForManualFallback();

        Instant failedAt = Instant.now().minusSeconds(5);
        AuditRecordSummary documentFailure = new AuditRecordSummary(
                "d1", actorId.toString(), "DOCUMENT_PROCESSING_FAILED", "Document", document.id().toString(),
                "FAILURE", correlationId.toString(),
                "Document processing failed: dependency=AI, code=AI_INVALID_RESPONSE, retryable=false, attempts=2",
                failedAt);
        AuditRecordSummary packageFailure = new AuditRecordSummary(
                "p1", actorId.toString(), "DOCUMENT_PROCESSING_FAILED", "DocumentPackage", pkg.id().toString(),
                "FAILURE", correlationId.toString(), "Package requires technical attention", failedAt.plusMillis(1));

        TechnicalExceptionQueryService service = new TechnicalExceptionQueryService(
                new FakePackageRepository(pkg), new FakeDocumentRepository(document), new EmptyLifecycleRepository(),
                new EmptyArtifactRepository(), new FixedAuditQuery(List.of(documentFailure, packageFailure)));

        var detail = service.execute(pkg.id(), tenantId);
        assertEquals("AI_INVALID_RESPONSE", detail.failureCode());
        assertEquals("AI_ANALYSIS", detail.failedStage());
        assertEquals(document.id(), detail.documentId());
        assertFalse(detail.retryable(), "business continuation must not expose technical retry from a non-FAILED package");
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
    }

    private static final class EmptyArtifactRepository implements ArtifactRepository {
        @Override public void save(Artifact artifact) { }
        @Override public Optional<Artifact> findByIdAndTenantId(ArtifactId id, TenantId tenantId) { return Optional.empty(); }
        @Override public List<Artifact> findByDocumentIdAndTenantId(DocumentId documentId, TenantId tenantId) { return List.of(); }
    }

    private record FixedAuditQuery(List<AuditRecordSummary> records) implements AuditQueryPort {
        @Override public List<AuditRecordSummary> findByFilter(AuditRecordFilter filter) {
            return records.stream()
                    .filter(r -> filter.actionOpt().map(v -> r.action().equals(v)).orElse(true))
                    .filter(r -> filter.resourceTypeOpt().map(v -> r.resourceType().equals(v)).orElse(true))
                    .filter(r -> filter.resourceIdOpt().map(v -> r.resourceId().equals(v)).orElse(true))
                    .limit(filter.limit()).toList();
        }
    }
}
