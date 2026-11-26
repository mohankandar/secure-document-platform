package com.securedoc.platform.application.intake.external;

import com.securedoc.platform.application.port.out.*;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.artifact.*;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.document.*;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.intake.*;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.MimeType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ExternalIntakeMaintenanceServiceTest {
    @Test
    void expiryCancelsPackageDeletesPartialBinaryAndRetainsIntakeMetadata() {
        TenantId tenant = TenantId.generate();
        ActorId actor = ActorId.generate();
        DocumentPackage pkg = DocumentPackage.create(tenant, PackageType.API_SUBMISSION, actor, PackageMetadata.empty());
        DocumentId receivedDocumentId = DocumentId.generate();
        Instant created = Instant.parse("2026-09-01T00:00:00Z");
        ExternalIntake intake = ExternalIntake.create(pkg.id(), tenant, IntegrationConnectionId.generate(),
                ExternalIntakeChannel.PUSH_API, "SRC-100", "1", "f".repeat(64), created,
                created.plus(Duration.ofHours(24)), List.of(
                        ExternalManifestDocument.expected("D1", "one.pdf", null),
                        ExternalManifestDocument.expected("D2", "two.pdf", null)));
        intake.recordReceived("D1", receivedDocumentId, "a".repeat(64), "idem-1", created.plusSeconds(10));

        Artifact artifact = Artifact.create(tenant, receivedDocumentId, ArtifactType.ORIGINAL,
                MimeType.pdf(), 10, Checksum.sha256("a".repeat(64)));
        artifact.markAvailable(ArtifactStorageReference.local("tenant/package/document/original"));

        FakeExternalIntakeRepository intakes = new FakeExternalIntakeRepository(intake);
        FakePackageRepository packages = new FakePackageRepository(pkg);
        FakeArtifactRepository artifacts = new FakeArtifactRepository(artifact);
        FakeStorage storage = new FakeStorage();
        List<AuditRecord> audits = new ArrayList<>();
        Clock clock = Clock.fixed(created.plus(Duration.ofHours(25)), ZoneOffset.UTC);

        ExternalIntakeMaintenanceService service = new ExternalIntakeMaintenanceService(intakes, packages, artifacts,
                new com.securedoc.platform.application.port.out.DirectErasure(artifacts, storage), new AuditService(audits::add), clock);
        boolean changed = service.expireIfDue(intake, actor, CorrelationId.generate());

        assertTrue(changed);
        assertEquals(ExternalIntakeStatus.INCOMPLETE_EXPIRED, intake.status());
        assertNotNull(intake.cleanupCompletedAt());
        assertEquals(PackageStatus.CANCELLED, pkg.status());
        assertEquals(ArtifactStatus.DELETED, artifact.status());
        assertEquals(1, storage.deleted.size());
        assertEquals("SRC-100", intake.externalSourcePackageId(), "source reconciliation metadata remains");
        assertFalse(audits.isEmpty());
    }

    /** Task 0102: a polled case with a file refused for good ends now, before its deadline, out of the dashboard. */
    @Test
    void aRefusedAttemptEndsNowItsCaseIsHiddenAndCancelledAndItsFilesErased() {
        TenantId tenant = TenantId.generate();
        ActorId actor = ActorId.generate();
        DocumentPackage pkg = DocumentPackage.create(tenant, PackageType.API_SUBMISSION, actor, PackageMetadata.empty());
        DocumentId receivedDocumentId = DocumentId.generate();
        Instant created = Instant.parse("2026-11-26T00:00:00Z");
        ExternalIntake intake = ExternalIntake.create(pkg.id(), tenant, IntegrationConnectionId.generate(),
                ExternalIntakeChannel.POLLING, "SRC-779001126006", "1", "f".repeat(64), created,
                created.plus(Duration.ofHours(24)), List.of(
                        ExternalManifestDocument.expected("D1", "good.pdf", null),
                        ExternalManifestDocument.expected("D2", "locked.pdf", null)));
        intake.recordReceived("D1", receivedDocumentId, "a".repeat(64), "idem-1", created.plusSeconds(10));
        Artifact artifact = Artifact.create(tenant, receivedDocumentId, ArtifactType.ORIGINAL,
                MimeType.pdf(), 10, Checksum.sha256("a".repeat(64)));
        artifact.markAvailable(ArtifactStorageReference.local("tenant/package/document/original"));
        FakeArtifactRepository artifacts = new FakeArtifactRepository(artifact);
        FakeStorage storage = new FakeStorage();
        List<AuditRecord> audits = new ArrayList<>();
        List<PackageId> hidden = new ArrayList<>();
        Clock clock = Clock.fixed(created.plus(Duration.ofMinutes(1)), ZoneOffset.UTC);
        ExternalIntakeMaintenanceService service = new ExternalIntakeMaintenanceService(new FakeExternalIntakeRepository(intake),
                new FakePackageRepository(pkg), artifacts,
                new com.securedoc.platform.application.port.out.DirectErasure(artifacts, storage), new AuditService(audits::add), clock)
                .withDashboardVisibility((id, t, a, at) -> hidden.add(id));

        assertTrue(service.refuseNow(intake, actor, CorrelationId.generate(), "LV-INT-008"));

        assertEquals(ExternalIntakeStatus.INCOMPLETE_EXPIRED, intake.status(), "ended before its deadline");
        assertEquals(PackageStatus.CANCELLED, pkg.status());
        assertEquals(List.of(pkg.id()), hidden);
        assertEquals(ArtifactStatus.DELETED, artifact.status(), "the good file stored first is erased");
        assertTrue(audits.stream().anyMatch(r -> r.detail() != null && r.detail().startsWith("Refused at intake: code=LV-INT-008")));
        assertFalse(service.refuseNow(intake, actor, CorrelationId.generate(), "LV-INT-008"), "once only");
        assertEquals(1, hidden.size(), "repeating changes nothing");
    }

    private static final class FakeExternalIntakeRepository implements ExternalIntakeRepository {
        private ExternalIntake intake;
        FakeExternalIntakeRepository(ExternalIntake intake) { this.intake = intake; }
        public Optional<ExternalIntake> findByTenantIdAndPackageId(TenantId t, PackageId p) { return Optional.of(intake); }
        public Optional<ExternalIntake> findByTenantIdAndPackageIdForUpdate(TenantId t, PackageId p) { return Optional.of(intake); }
        public Optional<ExternalIntake> findBySourceIdentity(TenantId t, IntegrationConnectionId c, String p, String r) { return Optional.empty(); }
        public List<ExternalIntake> findAwaitingExpiredBefore(Instant now, int limit) { return List.of(intake); }
        public List<ExternalIntake> findExpiredCleanupPending(int limit) { return intake.cleanupCompletedAt() == null ? List.of(intake) : List.of(); }
        public void save(ExternalIntake value) { intake = value; }
    }

    private static final class FakePackageRepository implements PackageRepository {
        private DocumentPackage pkg;
        FakePackageRepository(DocumentPackage pkg) { this.pkg = pkg; }
        public void save(DocumentPackage p) { pkg = p; }
        public Optional<DocumentPackage> findByIdAndTenantId(PackageId id, TenantId tenant) { return pkg.id().equals(id) && pkg.tenantId().equals(tenant) ? Optional.of(pkg) : Optional.empty(); }
        public List<DocumentPackage> findByTenantId(TenantId tenant, int offset, int limit) { return List.of(); }
        public long countByTenantId(TenantId tenant) { return 1; }
        public Optional<DocumentPackage> findByTenantIdAndIntakeFingerprint(TenantId tenant, String fingerprint) { return Optional.empty(); }
        public List<DocumentPackage> findByStatus(PackageStatus status, int limit) { return List.of(); }
    }

    private static final class FakeArtifactRepository implements ArtifactRepository {
        private final Artifact artifact;
        FakeArtifactRepository(Artifact artifact) { this.artifact = artifact; }
        public void save(Artifact a) { }
        public Optional<Artifact> findByIdAndTenantId(ArtifactId id, TenantId tenant) { return Optional.empty(); }
        public List<Artifact> findByDocumentIdAndTenantId(DocumentId id, TenantId tenant) {
            return artifact.documentId().equals(id) && artifact.tenantId().equals(tenant) ? List.of(artifact) : List.of();
        }
    }

    private static final class FakeStorage implements StoragePort {
        final List<ArtifactStorageReference> deleted = new ArrayList<>();
        public ArtifactStorageReference store(TenantId tenantId, String path, InputStream content, long sizeBytes, String mimeType) { return ArtifactStorageReference.local(path); }
        public InputStream retrieve(ArtifactStorageReference reference) { return new ByteArrayInputStream(new byte[0]); }
        public void delete(ArtifactStorageReference reference) { deleted.add(reference); }
    }
}
