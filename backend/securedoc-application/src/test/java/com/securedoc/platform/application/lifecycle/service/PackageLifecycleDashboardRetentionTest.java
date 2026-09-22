package com.securedoc.platform.application.lifecycle.service;

import com.securedoc.platform.application.lifecycle.command.PurgeDueRetentionCommand;
import com.securedoc.platform.application.port.out.*;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactStatus;
import com.securedoc.platform.domain.artifact.ArtifactStorageReference;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.document.*;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.lifecycle.PackageExportRecord;
import com.securedoc.platform.domain.lifecycle.PackageLifecycle;
import com.securedoc.platform.domain.lifecycle.TenantLifecyclePolicy;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.FileName;
import com.securedoc.platform.domain.vo.MimeType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PackageLifecycleDashboardRetentionTest {

    @Test
    void completedExportStaysOnDashboardUntilConfiguredOriginalRetentionPurgeThenIsHidden() {
        TenantId tenantId = TenantId.generate();
        ActorId actorId = ActorId.generate();
        CorrelationId correlationId = CorrelationId.generate();

        DocumentPackage pkg = DocumentPackage.create(
                tenantId, PackageType.MANUAL_UPLOAD, actorId, PackageMetadata.empty());
        Document document = Document.create(
                tenantId, pkg.id(), FileName.of("retention.pdf"), MimeType.pdf(), 128,
                Checksum.sha256("document"), DocumentType.PDF);
        pkg.addDocument(document);
        pkg.submit();
        pkg.startProcessing();
        document.startExtraction();
        document.completeExtraction(List.of(Page.withText(1, 612, 792, "synthetic")));
        document.startRedaction();
        document.completeProcessing();
        pkg.completeSuccessfully();

        MutablePackageRepository packages = new MutablePackageRepository(pkg);
        MutableDocumentRepository documents = new MutableDocumentRepository(document);
        MutableLifecycleRepository lifecycles = new MutableLifecycleRepository();
        MutableArtifactRepository artifacts = new MutableArtifactRepository();
        Artifact original = availableArtifact(tenantId, document.id(), ArtifactType.ORIGINAL, "original.pdf");
        artifacts.values.add(original);
        CapturingStorage storage = new CapturingStorage();
        CapturingVisibility visibility = new CapturingVisibility();
        CapturingAudit audit = new CapturingAudit();
        FixedPolicyRepository policies = new FixedPolicyRepository(tenantId, 48);

        PackageLifecycleService service = new PackageLifecycleService(
                policies, lifecycles, new CapturingExportRepository(), packages, visibility,
                documents, artifacts, storage, new AuditService(audit));

        service.recordSuccessfulExport(
                pkg.id(), tenantId, 1, "LOCAL_FOLDER",
                List.of(new PackageExportRecord.ExportedDocument(
                        document.id(), "retention.pdf", 128, Checksum.sha256("exported"), "local/retention.pdf")),
                correlationId);

        PackageLifecycle lifecycle = lifecycles.lifecycle.orElseThrow();
        Instant exportedAt = lifecycle.lastExportedAt().orElseThrow();
        Instant originalDeleteAt = lifecycle.originalDeleteAt().orElseThrow();
        assertEquals(Duration.ofHours(48), Duration.between(exportedAt, originalDeleteAt),
                "dashboard timing must follow the Business Admin original-retention policy, not a hard-coded 24h timer");

        var beforeDue = service.execute(new PurgeDueRetentionCommand(
                exportedAt.plus(Duration.ofHours(24)), 100, actorId, CorrelationId.generate()));

        assertEquals(0, beforeDue.packagesFullyPurged());
        assertTrue(visibility.hidden.isEmpty(), "completed package must remain visible before configured retention expires");
        assertEquals(ArtifactStatus.AVAILABLE, original.status());
        assertTrue(lifecycle.purgedAt().isEmpty());

        var atDue = service.execute(new PurgeDueRetentionCommand(
                originalDeleteAt, 100, actorId, CorrelationId.generate()));

        assertEquals(1, atDue.packagesFullyPurged());
        assertEquals(ArtifactStatus.DELETED, original.status(), "processing artifact should be purged first");
        assertEquals(1, storage.deleted.size());
        assertEquals(1, visibility.hidden.size(), "the same final-retention purge should hide the Dashboard row");
        assertEquals(pkg.id(), visibility.hidden.get(0).packageId());
        assertEquals(tenantId, visibility.hidden.get(0).tenantId());
        assertEquals(originalDeleteAt, visibility.hidden.get(0).hiddenAt());
        assertTrue(lifecycle.purgedAt().isPresent());
        assertSame(pkg, packages.pkg, "durable package metadata must remain; retention only changes visibility/artifacts");
    }

    @Test
    void neverExportedTechnicalAttentionPurgeHidesOperationalDashboardButKeepsDurablePackage() {
        TenantId tenantId = TenantId.generate();
        ActorId actorId = ActorId.generate();
        DocumentPackage pkg = DocumentPackage.create(
                tenantId, PackageType.MANUAL_UPLOAD, actorId, PackageMetadata.empty());
        Document document = Document.create(
                tenantId, pkg.id(), FileName.of("failed.pdf"), MimeType.pdf(), 64,
                Checksum.sha256("failed-document"), DocumentType.PDF);
        pkg.addDocument(document);
        pkg.submit();
        pkg.startProcessing();
        pkg.fail();

        MutablePackageRepository packages = new MutablePackageRepository(pkg);
        MutableLifecycleRepository lifecycles = new MutableLifecycleRepository();
        CapturingVisibility visibility = new CapturingVisibility();
        CapturingAudit audit = new CapturingAudit();
        PackageLifecycleService service = new PackageLifecycleService(
                new FixedPolicyRepository(tenantId, 24), lifecycles, new CapturingExportRepository(), packages, visibility,
                new EmptyDocumentRepository(), new MutableArtifactRepository(), new CapturingStorage(), new AuditService(audit));

        CorrelationId correlationId = CorrelationId.generate();
        service.recordTechnicalFailure(pkg.id(), tenantId, correlationId);
        PackageLifecycle lifecycle = lifecycles.lifecycle.orElseThrow();
        Instant holdUntil = lifecycle.technicalHoldUntil().orElseThrow();

        var result = service.execute(new PurgeDueRetentionCommand(
                holdUntil, 100, actorId, CorrelationId.generate()));

        assertEquals(1, result.packagesFullyPurged());
        assertEquals(1, visibility.hidden.size(),
                "expired Technical Attention must leave the operational Dashboard when its processing retention purges");
        assertEquals(pkg.id(), visibility.hidden.get(0).packageId());
        assertTrue(lifecycle.purgedAt().isPresent());
        assertSame(pkg, packages.pkg, "durable failed package history must remain after operational cleanup");
    }

    @Test
    void dashboardVisibilityFailureDoesNotMarkFinalPurgeCompleteAndCanRetry() {
        TenantId tenantId = TenantId.generate();
        ActorId actorId = ActorId.generate();
        DocumentPackage pkg = DocumentPackage.create(
                tenantId, PackageType.MANUAL_UPLOAD, actorId, PackageMetadata.empty());
        Document document = Document.create(
                tenantId, pkg.id(), FileName.of("retry.pdf"), MimeType.pdf(), 64,
                Checksum.sha256("retry-document"), DocumentType.PDF);
        pkg.addDocument(document);
        pkg.submit();
        pkg.startProcessing();
        document.startExtraction();
        document.completeExtraction(List.of(Page.withText(1, 612, 792, "synthetic")));
        document.startRedaction();
        document.completeProcessing();
        pkg.completeSuccessfully();

        MutablePackageRepository packages = new MutablePackageRepository(pkg);
        MutableDocumentRepository documents = new MutableDocumentRepository(document);
        MutableLifecycleRepository lifecycles = new MutableLifecycleRepository();
        MutableArtifactRepository artifacts = new MutableArtifactRepository();
        Artifact original = availableArtifact(tenantId, document.id(), ArtifactType.ORIGINAL, "retry-original.pdf");
        artifacts.values.add(original);
        FailingThenVisibility visibility = new FailingThenVisibility();
        CapturingAudit audit = new CapturingAudit();
        PackageLifecycleService service = new PackageLifecycleService(
                new FixedPolicyRepository(tenantId, 24), lifecycles, new CapturingExportRepository(), packages, visibility,
                documents, artifacts, new CapturingStorage(), new AuditService(audit));

        service.recordSuccessfulExport(
                pkg.id(), tenantId, 1, "LOCAL_FOLDER",
                List.of(new PackageExportRecord.ExportedDocument(
                        document.id(), "retry.pdf", 64, Checksum.sha256("retry-exported"), "local/retry.pdf")),
                CorrelationId.generate());
        PackageLifecycle lifecycle = lifecycles.lifecycle.orElseThrow();
        Instant dueAt = lifecycle.originalDeleteAt().orElseThrow();

        var failedAttempt = service.execute(new PurgeDueRetentionCommand(
                dueAt, 100, actorId, CorrelationId.generate()));

        assertEquals(0, failedAttempt.packagesFullyPurged());
        assertEquals(ArtifactStatus.DELETED, original.status());
        assertTrue(lifecycle.purgedAt().isEmpty(),
                "final lifecycle state must stay retryable when Dashboard visibility persistence fails");

        visibility.fail = false;
        var retry = service.execute(new PurgeDueRetentionCommand(
                dueAt.plusSeconds(1), 100, actorId, CorrelationId.generate()));

        assertEquals(1, retry.packagesFullyPurged());
        assertEquals(2, visibility.attempts);
        assertTrue(lifecycle.purgedAt().isPresent());
    }

    @Test
    void alreadyPurgedCompletedExportIsReconciledWithoutRepurgingArtifacts() {
        TenantId tenantId = TenantId.generate();
        ActorId actorId = ActorId.generate();
        DocumentPackage pkg = DocumentPackage.create(
                tenantId, PackageType.MANUAL_UPLOAD, actorId, PackageMetadata.empty());
        Document document = Document.create(
                tenantId, pkg.id(), FileName.of("legacy-purged.pdf"), MimeType.pdf(), 64,
                Checksum.sha256("legacy-purged-document"), DocumentType.PDF);
        pkg.addDocument(document);
        pkg.submit();
        pkg.startProcessing();
        document.startExtraction();
        document.completeExtraction(List.of(Page.withText(1, 612, 792, "synthetic")));
        document.startRedaction();
        document.completeProcessing();
        pkg.completeSuccessfully();

        Instant purgedAt = Instant.parse("2026-09-18T12:00:00Z");
        PackageLifecycle alreadyPurged = PackageLifecycle.reconstitute(
                pkg.id(), tenantId, 1, purgedAt.minus(Duration.ofHours(25)),
                purgedAt.minus(Duration.ofHours(1)), purgedAt.minus(Duration.ofHours(24)),
                null, purgedAt, purgedAt, purgedAt, false, purgedAt);

        MutableLifecycleRepository lifecycles = new MutableLifecycleRepository();
        lifecycles.lifecycle = Optional.of(alreadyPurged);
        lifecycles.includePurgedReconciliationCandidates = true;
        MutableArtifactRepository artifacts = new MutableArtifactRepository();
        Artifact legacyArtifact = availableArtifact(
                tenantId, document.id(), ArtifactType.ORIGINAL, "legacy-should-not-be-repurged.pdf");
        artifacts.values.add(legacyArtifact);
        CapturingStorage storage = new CapturingStorage();
        CapturingVisibility visibility = new CapturingVisibility();
        CapturingAudit audit = new CapturingAudit();

        PackageLifecycleService service = new PackageLifecycleService(
                new FixedPolicyRepository(tenantId, 24), lifecycles, new CapturingExportRepository(),
                new MutablePackageRepository(pkg), visibility, new MutableDocumentRepository(document),
                artifacts, storage, new AuditService(audit));

        var result = service.execute(new PurgeDueRetentionCommand(
                Instant.parse("2026-09-21T12:00:00Z"), 100, actorId, CorrelationId.generate()));

        assertEquals(0, result.packagesFullyPurged(),
                "reconciliation must not pretend to perform the already-completed final purge again");
        assertEquals(ArtifactStatus.AVAILABLE, legacyArtifact.status(),
                "upgrade reconciliation must not re-run artifact deletion");
        assertTrue(storage.deleted.isEmpty(), "upgrade reconciliation must only synchronize Dashboard visibility");
        assertEquals(1, visibility.hidden.size());
        assertEquals(pkg.id(), visibility.hidden.get(0).packageId());
        assertEquals(purgedAt, visibility.hidden.get(0).hiddenAt(),
                "reconciled visibility should retain the authoritative final-purge timestamp");
        assertTrue(audit.records.stream().anyMatch(record ->
                        record.action() == com.securedoc.platform.domain.audit.AuditAction.PACKAGE_HIDDEN_FROM_DASHBOARD),
                "reconciliation should leave an audit trail");
        assertTrue(alreadyPurged.purgedAt().isPresent(), "durable lifecycle history remains intact");
    }

    @Test
    void alreadyPurgedTechnicalAttentionIsReconciledWithoutRepurgingArtifacts() {
        TenantId tenantId = TenantId.generate();
        ActorId actorId = ActorId.generate();
        DocumentPackage pkg = DocumentPackage.create(
                tenantId, PackageType.MANUAL_UPLOAD, actorId, PackageMetadata.empty());
        Document document = Document.create(
                tenantId, pkg.id(), FileName.of("legacy-failed.pdf"), MimeType.pdf(), 64,
                Checksum.sha256("legacy-failed-document"), DocumentType.PDF);
        pkg.addDocument(document);
        pkg.submit();
        pkg.startProcessing();
        pkg.fail();

        Instant purgedAt = Instant.parse("2026-09-14T12:00:00Z");
        PackageLifecycle alreadyPurged = PackageLifecycle.reconstitute(
                pkg.id(), tenantId, 0, null, null, null,
                purgedAt.minus(Duration.ofHours(1)), null, purgedAt, purgedAt, false, purgedAt);
        MutableLifecycleRepository lifecycles = new MutableLifecycleRepository();
        lifecycles.lifecycle = Optional.of(alreadyPurged);
        lifecycles.includePurgedReconciliationCandidates = true;
        MutableArtifactRepository artifacts = new MutableArtifactRepository();
        Artifact legacyArtifact = availableArtifact(
                tenantId, document.id(), ArtifactType.ORIGINAL, "legacy-failed-should-not-be-repurged.pdf");
        artifacts.values.add(legacyArtifact);
        CapturingStorage storage = new CapturingStorage();
        CapturingVisibility visibility = new CapturingVisibility();

        PackageLifecycleService service = new PackageLifecycleService(
                new FixedPolicyRepository(tenantId, 24), lifecycles, new CapturingExportRepository(),
                new MutablePackageRepository(pkg), visibility, new MutableDocumentRepository(document),
                artifacts, storage, new AuditService(new CapturingAudit()));

        var result = service.execute(new PurgeDueRetentionCommand(
                Instant.parse("2026-09-21T12:00:00Z"), 100, actorId, CorrelationId.generate()));

        assertEquals(0, result.packagesFullyPurged());
        assertEquals(ArtifactStatus.AVAILABLE, legacyArtifact.status(),
                "legacy Technical Attention reconciliation must not repeat physical artifact deletion");
        assertTrue(storage.deleted.isEmpty());
        assertEquals(1, visibility.hidden.size());
        assertEquals(pkg.id(), visibility.hidden.get(0).packageId());
        assertEquals(purgedAt, visibility.hidden.get(0).hiddenAt(),
                "Dashboard visibility should inherit the historical purge timestamp");
        assertTrue(alreadyPurged.purgedAt().isPresent());
    }

    @Test
    void alreadyPurgedDashboardReconciliationFailureRetriesWithoutChangingRetentionHistory() {
        TenantId tenantId = TenantId.generate();
        ActorId actorId = ActorId.generate();
        DocumentPackage pkg = DocumentPackage.create(
                tenantId, PackageType.MANUAL_UPLOAD, actorId, PackageMetadata.empty());
        Document document = Document.create(
                tenantId, pkg.id(), FileName.of("legacy-retry.pdf"), MimeType.pdf(), 64,
                Checksum.sha256("legacy-retry-document"), DocumentType.PDF);
        pkg.addDocument(document);
        pkg.submit();
        pkg.startProcessing();
        document.startExtraction();
        document.completeExtraction(List.of(Page.withText(1, 612, 792, "synthetic")));
        document.startRedaction();
        document.completeProcessing();
        pkg.completeSuccessfully();

        Instant purgedAt = Instant.parse("2026-09-18T12:00:00Z");
        PackageLifecycle alreadyPurged = PackageLifecycle.reconstitute(
                pkg.id(), tenantId, 1, purgedAt.minus(Duration.ofHours(25)),
                purgedAt.minus(Duration.ofHours(1)), purgedAt.minus(Duration.ofHours(24)),
                null, purgedAt, purgedAt, purgedAt, false, purgedAt);
        MutableLifecycleRepository lifecycles = new MutableLifecycleRepository();
        lifecycles.lifecycle = Optional.of(alreadyPurged);
        lifecycles.includePurgedReconciliationCandidates = true;
        FailingThenVisibility visibility = new FailingThenVisibility();
        CapturingStorage storage = new CapturingStorage();

        PackageLifecycleService service = new PackageLifecycleService(
                new FixedPolicyRepository(tenantId, 24), lifecycles, new CapturingExportRepository(),
                new MutablePackageRepository(pkg), visibility, new MutableDocumentRepository(document),
                new MutableArtifactRepository(), storage, new AuditService(new CapturingAudit()));

        service.execute(new PurgeDueRetentionCommand(
                Instant.parse("2026-09-21T12:00:00Z"), 100, actorId, CorrelationId.generate()));
        assertEquals(1, visibility.attempts);
        assertEquals(purgedAt, alreadyPurged.purgedAt().orElseThrow(),
                "failed visibility reconciliation must not rewrite completed retention history");
        assertTrue(storage.deleted.isEmpty());

        visibility.fail = false;
        service.execute(new PurgeDueRetentionCommand(
                Instant.parse("2026-09-21T12:15:00Z"), 100, actorId, CorrelationId.generate()));

        assertEquals(2, visibility.attempts,
                "a still-visible previously purged package must be safe to retry on the next maintenance cycle");
        assertEquals(purgedAt, alreadyPurged.purgedAt().orElseThrow());
        assertTrue(storage.deleted.isEmpty());
    }

    private static Artifact availableArtifact(TenantId tenantId, DocumentId documentId,
                                              ArtifactType type, String path) {
        Artifact artifact = Artifact.create(tenantId, documentId, type, MimeType.pdf(), 64, Checksum.sha256(path));
        artifact.markAvailable(ArtifactStorageReference.local(path));
        return artifact;
    }

    private static final class FixedPolicyRepository implements TenantLifecyclePolicyRepository {
        private final TenantId tenantId;
        private final int originalRetentionHours;

        private FixedPolicyRepository(TenantId tenantId, int originalRetentionHours) {
            this.tenantId = tenantId;
            this.originalRetentionHours = originalRetentionHours;
        }

        @Override
        public Optional<TenantLifecyclePolicy> findByTenantId(TenantId requestedTenantId) {
            return requestedTenantId.equals(tenantId)
                    ? Optional.of(new TenantLifecyclePolicy(
                    tenantId, originalRetentionHours,
                    TenantLifecyclePolicy.DEFAULT_REDACTED_CACHE_HOURS,
                    TenantLifecyclePolicy.DEFAULT_NEVER_EXPORTED_HOLD_HOURS,
                    false, Instant.now()))
                    : Optional.empty();
        }

        @Override public void save(TenantLifecyclePolicy policy) { }
    }

    private static final class MutableLifecycleRepository implements PackageLifecycleRepository {
        private Optional<PackageLifecycle> lifecycle = Optional.empty();
        private boolean includePurgedReconciliationCandidates;

        @Override
        public Optional<PackageLifecycle> findByPackageIdAndTenantId(PackageId packageId, TenantId tenantId) {
            return lifecycle.filter(value -> value.packageId().equals(packageId) && value.tenantId().equals(tenantId));
        }

        @Override public void save(PackageLifecycle value) { lifecycle = Optional.of(value); }

        @Override
        public List<PackageLifecycle> findDueRedactedPurge(Instant now, int limit) {
            return lifecycle.filter(value -> value.isRedactedPurgeDue(now)).map(List::of).orElseGet(List::of);
        }

        @Override
        public List<PackageLifecycle> findDueFinalPurge(Instant now, int limit) {
            return lifecycle.filter(value -> value.isFinalPurgeDue(now)).map(List::of).orElseGet(List::of);
        }

        @Override
        public List<PackageLifecycle> findPurgedPackagesNeedingDashboardHide(int limit) {
            if (!includePurgedReconciliationCandidates) return List.of();
            return lifecycle
                    .filter(value -> value.purgedAt().isPresent())
                    .map(List::of)
                    .orElseGet(List::of);
        }
    }

    private static final class MutablePackageRepository implements PackageRepository {
        private DocumentPackage pkg;
        private MutablePackageRepository(DocumentPackage pkg) { this.pkg = pkg; }
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
        private MutableDocumentRepository(Document document) { this.document = document; }
        @Override public void save(Document value) { document = value; }
        @Override public Optional<Document> findByIdAndTenantId(DocumentId id, TenantId tenantId) {
            return document.id().equals(id) && document.tenantId().equals(tenantId) ? Optional.of(document) : Optional.empty();
        }
        @Override public List<Document> findByPackageIdAndTenantId(PackageId packageId, TenantId tenantId) {
            return document.packageId().equals(packageId) && document.tenantId().equals(tenantId) ? List.of(document) : List.of();
        }
    }

    private static final class EmptyDocumentRepository implements DocumentRepository {
        @Override public void save(Document document) { }
        @Override public Optional<Document> findByIdAndTenantId(DocumentId id, TenantId tenantId) { return Optional.empty(); }
        @Override public List<Document> findByPackageIdAndTenantId(PackageId packageId, TenantId tenantId) { return List.of(); }
    }

    private static final class MutableArtifactRepository implements ArtifactRepository {
        private final List<Artifact> values = new ArrayList<>();
        @Override public void save(Artifact artifact) { }
        @Override public Optional<Artifact> findByIdAndTenantId(ArtifactId id, TenantId tenantId) {
            return values.stream().filter(a -> a.id().equals(id) && a.tenantId().equals(tenantId)).findFirst();
        }
        @Override public List<Artifact> findByDocumentIdAndTenantId(DocumentId documentId, TenantId tenantId) {
            return values.stream().filter(a -> a.documentId().equals(documentId) && a.tenantId().equals(tenantId)).toList();
        }
    }

    private static final class CapturingStorage implements StoragePort {
        private final List<ArtifactStorageReference> deleted = new ArrayList<>();
        @Override public ArtifactStorageReference store(TenantId tenantId, String path, InputStream content, long sizeBytes, String mimeType) {
            return ArtifactStorageReference.local(path);
        }
        @Override public InputStream retrieve(ArtifactStorageReference reference) { return new ByteArrayInputStream(new byte[0]); }
        @Override public void delete(ArtifactStorageReference reference) { deleted.add(reference); }
    }

    private static final class CapturingVisibility implements PackageDashboardVisibilityPort {
        private final List<Hidden> hidden = new ArrayList<>();
        @Override public void hide(PackageId packageId, TenantId tenantId, ActorId actorId, Instant hiddenAt) {
            hidden.add(new Hidden(packageId, tenantId, actorId, hiddenAt));
        }
        private record Hidden(PackageId packageId, TenantId tenantId, ActorId actorId, Instant hiddenAt) { }
    }

    private static final class FailingThenVisibility implements PackageDashboardVisibilityPort {
        private boolean fail = true;
        private int attempts;
        @Override public void hide(PackageId packageId, TenantId tenantId, ActorId actorId, Instant hiddenAt) {
            attempts++;
            if (fail) throw new IllegalStateException("simulated dashboard visibility persistence failure");
        }
    }

    private static final class CapturingExportRepository implements PackageExportRecordRepository {
        private final List<PackageExportRecord> records = new ArrayList<>();
        @Override public void save(PackageExportRecord record) { records.add(record); }
        @Override public List<PackageExportRecord> findByPackageIdAndTenantId(PackageId packageId, TenantId tenantId) {
            return records.stream().filter(record -> record.packageId().equals(packageId) && record.tenantId().equals(tenantId)).toList();
        }
    }

    private static final class CapturingAudit implements AuditPort {
        private final List<AuditRecord> records = new ArrayList<>();
        @Override public void record(AuditRecord auditRecord) { records.add(auditRecord); }
    }
}
