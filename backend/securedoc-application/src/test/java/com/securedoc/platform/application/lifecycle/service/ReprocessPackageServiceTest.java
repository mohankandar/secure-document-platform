package com.securedoc.platform.application.lifecycle.service;

import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.lifecycle.command.ReprocessPackageCommand;
import com.securedoc.platform.application.licensing.LicenseTokenVerifier;
import com.securedoc.platform.application.licensing.TenantLicenseRepository;
import com.securedoc.platform.application.licensing.TenantLicensingService;
import com.securedoc.platform.application.port.out.*;
import com.securedoc.platform.application.processing.command.TriggerPackageProcessingCommand;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactStatus;
import com.securedoc.platform.domain.artifact.ArtifactStorageReference;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.document.*;
import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.lifecycle.PackageExportRecord;
import com.securedoc.platform.domain.lifecycle.PackageLifecycle;
import com.securedoc.platform.domain.lifecycle.TenantLifecyclePolicy;
import com.securedoc.platform.domain.licensing.LicenseVerificationResult;
import com.securedoc.platform.domain.licensing.TenantLicenseRecord;
import com.securedoc.platform.domain.tenant.Tenant;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.FileName;
import com.securedoc.platform.domain.vo.MimeType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ReprocessPackageServiceTest {

    @Test
    void startsNewCycleAsSubmittedDisablesSecondReprocessAndDispatchesOnce() {
        TenantId tenantId = TenantId.generate();
        ActorId actorId = ActorId.generate();
        CorrelationId correlationId = CorrelationId.generate();
        DocumentPackage pkg = DocumentPackage.create(
                tenantId, PackageType.MANUAL_UPLOAD, actorId, PackageMetadata.empty());
        Document document = Document.create(
                tenantId, pkg.id(), FileName.of("reprocess.pdf"), MimeType.pdf(), 128,
                Checksum.sha256("document"), DocumentType.PDF);
        pkg.addDocument(document);
        pkg.submit();
        pkg.startProcessing();
        document.startExtraction();
        document.completeExtraction(List.of(Page.withText(1, 612, 792, "synthetic")));
        document.startRedaction();
        document.completeProcessing();
        pkg.completeSuccessfully();

        Instant now = Instant.now();
        PackageLifecycle lifecycle = PackageLifecycle.reconstitute(
                pkg.id(), tenantId, 1, now.minus(1, ChronoUnit.HOURS),
                now.plus(48, ChronoUnit.HOURS), now.plus(12, ChronoUnit.HOURS),
                null, null, null, null, false, now);

        MutablePackageRepository packages = new MutablePackageRepository(pkg);
        MutableDocumentRepository documents = new MutableDocumentRepository(document);
        MutableLifecycleRepository lifecycles = new MutableLifecycleRepository(lifecycle);
        MutableArtifactRepository artifacts = new MutableArtifactRepository();
        Artifact original = availableArtifact(tenantId, document.id(), ArtifactType.ORIGINAL, "original.pdf");
        Artifact redacted = availableArtifact(tenantId, document.id(), ArtifactType.REDACTED, "redacted.pdf");
        artifacts.values.add(original);
        artifacts.values.add(redacted);
        CapturingStorage storage = new CapturingStorage();
        CapturingAudit audit = new CapturingAudit();
        CapturingDispatcher dispatcher = new CapturingDispatcher();

        PackageLifecycleService lifecycleService = new PackageLifecycleService(
                new DefaultPolicyRepository(), lifecycles, new EmptyExportRepository(), packages,
                (packageId, tenant, actor, hiddenAt) -> { }, documents, artifacts, storage, new AuditService(audit));
        AuditService auditService = new AuditService(audit);
        ReprocessPackageService service = new ReprocessPackageService(
                packages, documents, new EmptyFindingRepository(), artifacts,
                lifecycleService, dispatcher, auditService, disabledLicensing(auditService));

        assertTrue(lifecycleService.execute(pkg.id(), tenantId).canReprocess());

        var result = service.execute(new ReprocessPackageCommand(
                pkg.id(), tenantId, actorId, correlationId));

        assertEquals(PackageStatus.SUBMITTED, result.status());
        assertEquals(PackageStatus.SUBMITTED, pkg.status());
        assertEquals(DocumentStatus.UPLOADED, document.status());
        assertEquals(2, result.nextExportCycle());
        assertFalse(lifecycleService.execute(pkg.id(), tenantId).canReprocess(),
                "a running cycle must never advertise another reprocess action");
        assertEquals(ArtifactStatus.AVAILABLE, original.status(), "the retained original must survive");
        assertEquals(ArtifactStatus.DELETED, redacted.status(), "prior transient output is removed");
        assertEquals(1, storage.deleted.size());
        assertEquals(1, dispatcher.commands.size());
        assertEquals(pkg.id(), dispatcher.commands.get(0).packageId());
        assertTrue(audit.records.stream().anyMatch(record -> record.action() == AuditAction.PACKAGE_REPROCESS_REQUESTED));

        assertThrows(ValidationException.class, () -> service.execute(new ReprocessPackageCommand(
                pkg.id(), tenantId, actorId, CorrelationId.generate())));
        assertEquals(1, dispatcher.commands.size(), "duplicate reprocess request must not dispatch again");
    }


    private static TenantLicensingService disabledLicensing(AuditService auditService) {
        TenantLicenseRepository licenses = new TenantLicenseRepository() {
            @Override public TenantLicenseRecord save(TenantLicenseRecord record) { return record; }
            @Override public void activate(TenantId tenantId, java.util.UUID licenseRecordId, java.util.UUID actorId) { }
            @Override public Optional<TenantLicenseRecord> findActive(TenantId tenantId) { return Optional.empty(); }
            @Override public List<TenantLicenseRecord> findHistory(TenantId tenantId) { return List.of(); }
        };
        TenantRepository tenants = new TenantRepository() {
            @Override public Optional<Tenant> findById(TenantId tenantId) { return Optional.empty(); }
            @Override public Optional<Tenant> findBySlug(String slug) { return Optional.empty(); }
            @Override public List<Tenant> findAll() { return List.of(); }
        };
        LicenseTokenVerifier verifier = new LicenseTokenVerifier() {
            @Override public LicenseVerificationResult verify(String signedToken, TenantId expectedTenantId, Instant now) { throw new AssertionError("not used"); }
            @Override public boolean isVerificationKeyConfigured() { return false; }
        };
        return new TenantLicensingService(licenses, tenants, verifier, auditService, false);
    }

    private static Artifact availableArtifact(TenantId tenantId, DocumentId documentId,
                                              ArtifactType type, String path) {
        Artifact artifact = Artifact.create(tenantId, documentId, type, MimeType.pdf(), 64, Checksum.sha256(path));
        artifact.markAvailable(ArtifactStorageReference.local(path));
        return artifact;
    }

    private static final class MutablePackageRepository implements PackageRepository {
        private DocumentPackage pkg;
        MutablePackageRepository(DocumentPackage pkg) { this.pkg = pkg; }
        @Override public void save(DocumentPackage value) { pkg = value; }
        @Override public Optional<DocumentPackage> findByIdAndTenantId(PackageId id, TenantId tenantId) {
            return pkg.id().equals(id) && pkg.tenantId().equals(tenantId) ? Optional.of(pkg) : Optional.empty();
        }
        @Override public List<DocumentPackage> findByTenantId(TenantId tenantId, int offset, int limit) { return List.of(pkg); }
        @Override public long countByTenantId(TenantId tenantId) { return 1; }
        @Override public Optional<DocumentPackage> findByTenantIdAndIntakeFingerprint(TenantId tenantId, String fingerprint) { return Optional.empty(); }
        @Override public List<DocumentPackage> findByStatus(PackageStatus status, int limit) { return pkg.status() == status ? List.of(pkg) : List.of(); }
    }

    private static final class MutableDocumentRepository implements DocumentRepository {
        private Document document;
        MutableDocumentRepository(Document document) { this.document = document; }
        @Override public void save(Document value) { document = value; }
        @Override public Optional<Document> findByIdAndTenantId(DocumentId id, TenantId tenantId) {
            return document.id().equals(id) && document.tenantId().equals(tenantId) ? Optional.of(document) : Optional.empty();
        }
        @Override public List<Document> findByPackageIdAndTenantId(PackageId packageId, TenantId tenantId) {
            return document.packageId().equals(packageId) && document.tenantId().equals(tenantId) ? List.of(document) : List.of();
        }
    }

    private static final class MutableLifecycleRepository implements PackageLifecycleRepository {
        private PackageLifecycle lifecycle;
        MutableLifecycleRepository(PackageLifecycle lifecycle) { this.lifecycle = lifecycle; }
        @Override public Optional<PackageLifecycle> findByPackageIdAndTenantId(PackageId packageId, TenantId tenantId) {
            return lifecycle.packageId().equals(packageId) && lifecycle.tenantId().equals(tenantId) ? Optional.of(lifecycle) : Optional.empty();
        }
        @Override public void save(PackageLifecycle value) { lifecycle = value; }
        @Override public List<PackageLifecycle> findDueRedactedPurge(Instant now, int limit) { return List.of(); }
        @Override public List<PackageLifecycle> findDueFinalPurge(Instant now, int limit) { return List.of(); }
        @Override public List<PackageLifecycle> findPurgedCompletedExportsNeedingDashboardHide(int limit) { return List.of(); }
    }

    private static final class DefaultPolicyRepository implements TenantLifecyclePolicyRepository {
        @Override public Optional<TenantLifecyclePolicy> findByTenantId(TenantId tenantId) {
            return Optional.of(TenantLifecyclePolicy.defaults(tenantId));
        }
        @Override public void save(TenantLifecyclePolicy policy) { }
    }

    private static final class EmptyExportRepository implements PackageExportRecordRepository {
        @Override public void save(PackageExportRecord record) { }
        @Override public List<PackageExportRecord> findByPackageIdAndTenantId(PackageId packageId, TenantId tenantId) { return List.of(); }
    }

    private static final class MutableArtifactRepository implements ArtifactRepository {
        final List<Artifact> values = new ArrayList<>();
        @Override public void save(Artifact artifact) { }
        @Override public Optional<Artifact> findByIdAndTenantId(ArtifactId id, TenantId tenantId) {
            return values.stream().filter(a -> a.id().equals(id) && a.tenantId().equals(tenantId)).findFirst();
        }
        @Override public List<Artifact> findByDocumentIdAndTenantId(DocumentId documentId, TenantId tenantId) {
            return values.stream().filter(a -> a.documentId().equals(documentId) && a.tenantId().equals(tenantId)).toList();
        }
    }

    private static final class EmptyFindingRepository implements FindingRepository {
        @Override public void save(Finding finding) { }
        @Override public void saveAll(List<Finding> findings) { }
        @Override public Optional<Finding> findByIdAndTenantId(FindingId id, TenantId tenantId) { return Optional.empty(); }
        @Override public List<Finding> findByDocumentIdAndTenantId(DocumentId documentId, TenantId tenantId) { return List.of(); }
        @Override public void deleteByDocumentIdAndTenantId(DocumentId documentId, TenantId tenantId) { }
    }

    private static final class CapturingStorage implements StoragePort {
        final List<ArtifactStorageReference> deleted = new ArrayList<>();
        @Override public ArtifactStorageReference store(TenantId tenantId, String path, InputStream content, long sizeBytes, String mimeType) {
            return ArtifactStorageReference.local(path);
        }
        @Override public InputStream retrieve(ArtifactStorageReference reference) { return new ByteArrayInputStream(new byte[0]); }
        @Override public void delete(ArtifactStorageReference reference) { deleted.add(reference); }
    }

    private static final class CapturingDispatcher implements PackageProcessingDispatcher {
        final List<TriggerPackageProcessingCommand> commands = new ArrayList<>();
        @Override public void dispatch(TriggerPackageProcessingCommand command) { commands.add(command); }
    }

    private static final class CapturingAudit implements AuditPort {
        final List<AuditRecord> records = new ArrayList<>();
        @Override public void record(AuditRecord auditRecord) { records.add(auditRecord); }
    }
}
