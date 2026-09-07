package com.securedoc.platform.application.manual.service;

import com.securedoc.platform.application.audit.query.AuditRecordFilter;
import com.securedoc.platform.application.audit.query.AuditRecordSummary;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.manual.command.AddManualRedactionRegionCommand;
import com.securedoc.platform.application.manual.command.ManualFallbackCommand;
import com.securedoc.platform.application.manual.command.RemoveManualRedactionRegionCommand;
import com.securedoc.platform.application.manual.command.SubmitManualFallbackCommand;
import com.securedoc.platform.application.port.in.GetTechnicalExceptionQuery;
import com.securedoc.platform.application.port.out.*;
import com.securedoc.platform.application.processing.command.TriggerPackageProcessingCommand;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.application.technical.model.TechnicalExceptionDetail;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactStorageReference;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.document.*;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.finding.FindingStatus;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.FileName;
import com.securedoc.platform.domain.vo.MimeType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ManualFallbackServiceTest {
    @Test
    void startsAiFailureFallbackWithoutDiscardingPagesAndCreatesConfirmedManualRegion() {
        Fixture f = new Fixture("AI_ANALYSIS");
        var detail = f.service.execute(new ManualFallbackCommand(f.pkg.id(), f.tenantId, f.actorId, f.correlationId));
        assertEquals(PackageStatus.IN_REVIEW, f.pkg.status());
        assertEquals(DocumentStatus.REDACTING, f.document.status());
        assertEquals(2, f.document.pageCount(), "manual fallback must preserve extraction/OCR page metadata");
        assertEquals(1, detail.documents().size());

        var region = f.service.execute(new AddManualRedactionRegionCommand(
                f.pkg.id(), f.document.id(), 1, EntityType.SSN,
                0.10, 0.20, 0.25, 0.04, f.tenantId, f.actorId, f.correlationId));
        Finding finding = f.findings.findByIdAndTenantId(region.findingId(), f.tenantId).orElseThrow();
        assertEquals(FindingStatus.CONFIRMED, finding.status());
        assertEquals("MANUAL_FALLBACK", finding.detectorSource().detectorName());
        assertTrue(finding.location().hasBoundingBox());
        assertTrue(f.audit.records.stream().noneMatch(r -> r.detail() != null && r.detail().contains("123-45")));
    }

    @Test
    void rejectsFallbackForNonAiTechnicalFailure() {
        Fixture f = new Fixture("REDACTION");
        assertThrows(ValidationException.class, () -> f.service.execute(
                new ManualFallbackCommand(f.pkg.id(), f.tenantId, f.actorId, f.correlationId)));
        assertEquals(PackageStatus.FAILED, f.pkg.status());
        assertEquals(DocumentStatus.FAILED, f.document.status());
    }

    @Test
    void requiresBusinessReviewAttestationAndDispatchesNormalPipelineExactlyOnce() {
        Fixture f = new Fixture("AI_ANALYSIS");
        f.service.execute(new ManualFallbackCommand(f.pkg.id(), f.tenantId, f.actorId, f.correlationId));
        var region = f.service.execute(new AddManualRedactionRegionCommand(
                f.pkg.id(), f.document.id(), 2, EntityType.CREDIT_SCORE,
                0.25, 0.35, 0.12, 0.04, f.tenantId, f.actorId, f.correlationId));

        assertThrows(ValidationException.class, () -> f.service.execute(new SubmitManualFallbackCommand(
                f.pkg.id(), f.tenantId, f.actorId, f.correlationId, false)));
        assertEquals(0, f.dispatcher.commands.size());

        f.service.execute(new RemoveManualRedactionRegionCommand(
                f.pkg.id(), region.findingId(), f.tenantId, f.actorId, f.correlationId));
        assertEquals(FindingStatus.OVERRIDDEN,
                f.findings.findByIdAndTenantId(region.findingId(), f.tenantId).orElseThrow().status());

        f.service.execute(new SubmitManualFallbackCommand(
                f.pkg.id(), f.tenantId, f.actorId, f.correlationId, true));
        assertEquals(PackageStatus.PROCESSING, f.pkg.status());
        assertEquals(1, f.dispatcher.commands.size());
        assertEquals(f.pkg.id(), f.dispatcher.commands.get(0).packageId());
    }

    private static final class Fixture {
        final TenantId tenantId = TenantId.generate();
        final ActorId actorId = ActorId.generate();
        final CorrelationId correlationId = CorrelationId.generate();
        final DocumentPackage pkg = DocumentPackage.create(tenantId, PackageType.MANUAL_UPLOAD, actorId, PackageMetadata.empty());
        final Document document = Document.create(tenantId, pkg.id(), FileName.of("fallback.pdf"), MimeType.pdf(), 100,
                Checksum.sha256("fixture"), DocumentType.PDF);
        final MutablePackageRepository packages = new MutablePackageRepository(pkg);
        final MutableDocumentRepository documents = new MutableDocumentRepository(document);
        final MutableFindingRepository findings = new MutableFindingRepository();
        final MutableArtifactRepository artifacts;
        final MutableAudit audit = new MutableAudit();
        final CapturingDispatcher dispatcher = new CapturingDispatcher();
        final ManualFallbackService service;

        Fixture(String failedStage) {
            pkg.addDocument(document); pkg.submit(); pkg.startProcessing();
            document.startExtraction();
            document.completeExtraction(List.of(Page.withText(1, 612, 792, "synthetic page one"),
                    Page.withText(2, 612, 792, "synthetic page two")));
            document.fail(); pkg.fail();

            Artifact original = Artifact.create(tenantId, document.id(), ArtifactType.ORIGINAL,
                    MimeType.pdf(), 100, Checksum.sha256("original"));
            original.markAvailable(ArtifactStorageReference.local("fallback.pdf"));
            artifacts = new MutableArtifactRepository(original);

            GetTechnicalExceptionQuery technical = (packageId, queryTenant) -> new TechnicalExceptionDetail(
                    packageId, document.id(), "FAILED", "DEPENDENCY_RESPONSE_ERROR", failedStage,
                    "AI", "AI_INVALID_RESPONSE", false, 2, Instant.now(), correlationId.toString(),
                    Instant.now().plusSeconds(3600), "safe diagnostic");
            service = new ManualFallbackService(packages, documents, findings, artifacts,
                    new FakePolicyRepository(), audit, technical, dispatcher, new AuditService(audit));
        }
    }

    private static final class MutablePackageRepository implements PackageRepository {
        private DocumentPackage pkg;
        MutablePackageRepository(DocumentPackage pkg) { this.pkg = pkg; }
        @Override public void save(DocumentPackage value) { pkg = value; }
        @Override public Optional<DocumentPackage> findByIdAndTenantId(PackageId id, TenantId tenantId) { return pkg.id().equals(id) && pkg.tenantId().equals(tenantId) ? Optional.of(pkg) : Optional.empty(); }
        @Override public List<DocumentPackage> findByTenantId(TenantId tenantId, int offset, int limit) { return List.of(pkg); }
        @Override public long countByTenantId(TenantId tenantId) { return 1; }
        @Override public Optional<DocumentPackage> findByTenantIdAndIntakeFingerprint(TenantId tenantId, String fingerprint) { return Optional.empty(); }
        @Override public List<DocumentPackage> findByStatus(PackageStatus status, int limit) { return pkg.status() == status ? List.of(pkg) : List.of(); }
    }

    private static final class MutableDocumentRepository implements DocumentRepository {
        private Document document;
        MutableDocumentRepository(Document document) { this.document = document; }
        @Override public void save(Document value) { document = value; }
        @Override public Optional<Document> findByIdAndTenantId(DocumentId id, TenantId tenantId) { return document.id().equals(id) && document.tenantId().equals(tenantId) ? Optional.of(document) : Optional.empty(); }
        @Override public List<Document> findByPackageIdAndTenantId(PackageId packageId, TenantId tenantId) { return document.packageId().equals(packageId) && document.tenantId().equals(tenantId) ? List.of(document) : List.of(); }
    }

    private static final class MutableFindingRepository implements FindingRepository {
        private final List<Finding> values = new ArrayList<>();
        @Override public void save(Finding finding) { values.removeIf(f -> f.id().equals(finding.id())); values.add(finding); }
        @Override public void saveAll(List<Finding> findings) { findings.forEach(this::save); }
        @Override public Optional<Finding> findByIdAndTenantId(FindingId id, TenantId tenantId) { return values.stream().filter(f -> f.id().equals(id) && f.tenantId().equals(tenantId)).findFirst(); }
        @Override public List<Finding> findByDocumentIdAndTenantId(DocumentId documentId, TenantId tenantId) { return values.stream().filter(f -> f.documentId().equals(documentId) && f.tenantId().equals(tenantId)).toList(); }
        @Override public void deleteByDocumentIdAndTenantId(DocumentId documentId, TenantId tenantId) { values.removeIf(f -> f.documentId().equals(documentId) && f.tenantId().equals(tenantId)); }
    }

    private record MutableArtifactRepository(Artifact original) implements ArtifactRepository {
        @Override public void save(Artifact artifact) { }
        @Override public Optional<Artifact> findByIdAndTenantId(ArtifactId id, TenantId tenantId) { return original.id().equals(id) && original.tenantId().equals(tenantId) ? Optional.of(original) : Optional.empty(); }
        @Override public List<Artifact> findByDocumentIdAndTenantId(DocumentId documentId, TenantId tenantId) { return original.documentId().equals(documentId) && original.tenantId().equals(tenantId) ? List.of(original) : List.of(); }
    }

    private static final class FakePolicyRepository implements TenantRedactionPolicyRepository {
        @Override public Optional<TenantRedactionPolicy> findByTenantId(TenantId tenantId) { return Optional.of(TenantRedactionPolicy.defaults(tenantId)); }
        @Override public void save(TenantRedactionPolicy policy) { }
    }

    private static final class CapturingDispatcher implements PackageProcessingDispatcher {
        final List<TriggerPackageProcessingCommand> commands = new ArrayList<>();
        @Override public void dispatch(TriggerPackageProcessingCommand command) { commands.add(command); }
    }

    private static final class MutableAudit implements AuditPort, AuditQueryPort {
        final List<AuditRecord> records = new ArrayList<>();
        @Override public void record(AuditRecord record) { records.add(record); }
        @Override public List<AuditRecordSummary> findByFilter(AuditRecordFilter filter) {
            return records.stream().filter(r -> r.tenantId().equals(filter.tenantId()))
                    .filter(r -> filter.actionOpt().map(v -> r.action().name().equals(v)).orElse(true))
                    .filter(r -> filter.resourceTypeOpt().map(v -> r.resourceType().equals(v)).orElse(true))
                    .filter(r -> filter.resourceIdOpt().map(v -> r.resourceId().equals(v)).orElse(true))
                    .map(r -> new AuditRecordSummary(r.id().toString(), r.actorId().toString(), r.action().name(), r.resourceType(), r.resourceId(), r.outcome().name(), r.correlationId().toString(), r.detail(), r.timestamp()))
                    .limit(filter.limit()).toList();
        }
    }
}
