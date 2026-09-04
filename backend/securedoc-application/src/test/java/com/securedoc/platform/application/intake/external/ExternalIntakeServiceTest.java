package com.securedoc.platform.application.intake.external;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.IntakeConflictException;
import com.securedoc.platform.application.integration.IntegrationConnectionService;
import com.securedoc.platform.application.intake.command.*;
import com.securedoc.platform.application.port.in.*;
import com.securedoc.platform.application.port.out.*;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.artifact.*;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.document.*;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.integration.*;
import com.securedoc.platform.domain.intake.*;
import com.securedoc.platform.domain.vo.Checksum;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ExternalIntakeServiceTest {
    @Test
    void expiredImmutableRevisionCanRetryAsNewAttemptButChangedPayloadRequiresNewRevision() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-01T00:00:00Z"));
        TenantId tenant = TenantId.generate();
        ActorId actor = ActorId.generate();
        AuthenticatedPrincipal principal = AuthenticatedPrincipal.of(actor, tenant, "los-client", Set.of());
        List<AuditRecord> audits = new ArrayList<>();
        AuditService auditService = new AuditService(audits::add);

        FakeConnectionRepository connections = new FakeConnectionRepository();
        IntegrationConnection source = IntegrationConnection.create(tenant, "LOS", null,
                IntegrationPurpose.INGESTION_SOURCE, IntegrationDirection.INBOUND, IntegrationProviderType.PUSH_API,
                IntegrationAuthenticationType.JWT, "los-client", null, null, null, 60, 2, 24,
                10, 10_000_000L, 100, IntegrationAcknowledgementMode.NONE, true, clock.instant());
        connections.save(source);
        IntegrationConnectionService connectionService = new IntegrationConnectionService(connections, auditService);

        FakeExternalIntakeRepository intakes = new FakeExternalIntakeRepository();
        FakePackageRepository packages = new FakePackageRepository();
        CreatePackageUseCase create = (command, p) -> {
            DocumentPackage pkg = DocumentPackage.create(p.tenantId(), command.packageType(), p.actorId(), PackageMetadata.of(command.metadata()));
            packages.save(pkg);
            return CreatePackageResult.of(pkg.id(), BusinessRecordId.generate(), pkg.status(), pkg.createdAt());
        };
        UploadDocumentUseCase upload = (command, p) -> { throw new AssertionError("upload not expected"); };
        SubmitPackageUseCase submit = (command, p) -> { throw new AssertionError("submit not expected"); };
        PackageProcessingDispatcher dispatcher = command -> { throw new AssertionError("dispatch not expected"); };
        ChecksumPort checksum = input -> Checksum.sha256("a".repeat(64));
        PdfIntakeValidationPort pdf = input -> new PdfIntakeValidationPort.PdfInfo(1);
        ExternalIntakeMaintenanceService maintenance = new ExternalIntakeMaintenanceService(intakes, packages,
                new EmptyArtifactRepository(), new NoopStorage(), auditService, clock);
        ExternalIntakeService service = new ExternalIntakeService(connectionService, intakes, packages, create, upload,
                submit, dispatcher, checksum, pdf, auditService, maintenance, clock);

        PushIntakeCreateInput initial = new PushIntakeCreateInput("SRC-PKG-1", "1", Map.of("contractNumber", "C-1"),
                Map.of(), List.of(new PushIntakeCreateInput.ManifestDocumentInput("DOC-1", "one.pdf", null)));
        ExternalIntakeSnapshot attempt1 = service.createPushIntake(initial, principal, CorrelationId.generate());
        assertEquals(1, attempt1.attemptNumber());

        clock.set(clock.instant().plus(Duration.ofHours(25)));
        ExternalIntakeSnapshot expired = service.status(PackageId.of(attempt1.packageId()), principal, CorrelationId.generate());
        assertEquals(ExternalIntakeStatus.INCOMPLETE_EXPIRED, expired.intakeStatus());
        assertEquals(PackageStatus.CANCELLED, expired.packageStatus());

        PushIntakeCreateInput retry = new PushIntakeCreateInput("SRC-PKG-1", "1", Map.of("contractNumber", "C-1"),
                Map.of(), List.of(new PushIntakeCreateInput.ManifestDocumentInput("DOC-1", "one.pdf", null)), true);
        ExternalIntakeSnapshot attempt2 = service.createPushIntake(retry, principal, CorrelationId.generate());
        assertEquals(2, attempt2.attemptNumber());
        assertFalse(attempt1.packageId().equals(attempt2.packageId()));
        assertEquals("1", attempt2.sourceRevision());

        PushIntakeCreateInput changedSameRevision = new PushIntakeCreateInput("SRC-PKG-1", "1", Map.of("contractNumber", "C-1"),
                Map.of(), List.of(new PushIntakeCreateInput.ManifestDocumentInput("DOC-CHANGED", "changed.pdf", null)), true);
        IntakeConflictException conflict = assertThrows(IntakeConflictException.class,
                () -> service.createPushIntake(changedSameRevision, principal, CorrelationId.generate()));
        assertEquals("SOURCE_REVISION_REUSED", conflict.errorCode());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        MutableClock(Instant instant) { this.instant = instant; }
        void set(Instant value) { instant = value; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return instant; }
    }

    private static final class FakeConnectionRepository implements IntegrationConnectionRepository {
        private final List<IntegrationConnection> values = new ArrayList<>();
        public List<IntegrationConnection> findByTenantId(TenantId t) { return values.stream().filter(v -> v.tenantId().equals(t)).toList(); }
        public Optional<IntegrationConnection> findByTenantIdAndId(TenantId t, IntegrationConnectionId id) { return values.stream().filter(v -> v.tenantId().equals(t) && v.id().equals(id)).findFirst(); }
        public Optional<IntegrationConnection> findEnabledPushSourceByTenantIdAndClientPrincipal(TenantId t, String p) { return values.stream().filter(v -> v.tenantId().equals(t) && v.enabled() && v.providerType() == IntegrationProviderType.PUSH_API && p.equals(v.clientPrincipal())).findFirst(); }
        public boolean existsByTenantIdAndNameIgnoreCase(TenantId t, String n, IntegrationConnectionId e) { return false; }
        public boolean existsByTenantIdAndClientPrincipal(TenantId t, String p, IntegrationConnectionId e) { return false; }
        public void save(IntegrationConnection c) { values.add(c); }
    }

    private static final class FakeExternalIntakeRepository implements ExternalIntakeRepository {
        private final List<ExternalIntake> values = new ArrayList<>();
        public Optional<ExternalIntake> findByTenantIdAndPackageId(TenantId t, PackageId p) { return values.stream().filter(v -> v.tenantId().equals(t) && v.packageId().equals(p)).findFirst(); }
        public Optional<ExternalIntake> findByTenantIdAndPackageIdForUpdate(TenantId t, PackageId p) { return findByTenantIdAndPackageId(t, p); }
        public Optional<ExternalIntake> findBySourceIdentity(TenantId t, IntegrationConnectionId c, String p, String r) { return values.stream().filter(v -> v.tenantId().equals(t) && v.integrationConnectionId().equals(c) && v.externalSourcePackageId().equals(p) && v.sourceRevision().equals(r)).max(Comparator.comparingInt(ExternalIntake::attemptNumber)); }
        public List<ExternalIntake> findAwaitingExpiredBefore(Instant n, int l) { return values.stream().filter(v -> v.status() == ExternalIntakeStatus.AWAITING_DOCUMENTS && !v.expiresAt().isAfter(n)).limit(l).toList(); }
        public List<ExternalIntake> findExpiredCleanupPending(int l) { return values.stream().filter(v -> v.status() == ExternalIntakeStatus.INCOMPLETE_EXPIRED && v.cleanupCompletedAt() == null).limit(l).toList(); }
        public void save(ExternalIntake i) { if (values.stream().noneMatch(v -> v.packageId().equals(i.packageId()))) values.add(i); }
    }

    private static final class FakePackageRepository implements PackageRepository {
        private final Map<UUID, DocumentPackage> values = new HashMap<>();
        public void save(DocumentPackage p) { values.put(p.id().value(), p); }
        public Optional<DocumentPackage> findByIdAndTenantId(PackageId id, TenantId t) { return Optional.ofNullable(values.get(id.value())).filter(p -> p.tenantId().equals(t)); }
        public List<DocumentPackage> findByTenantId(TenantId t, int o, int l) { return List.of(); }
        public long countByTenantId(TenantId t) { return values.size(); }
        public Optional<DocumentPackage> findByTenantIdAndIntakeFingerprint(TenantId t, String f) { return Optional.empty(); }
        public List<DocumentPackage> findByStatus(PackageStatus s, int l) { return List.of(); }
    }

    private static final class EmptyArtifactRepository implements ArtifactRepository {
        public void save(Artifact a) { }
        public Optional<Artifact> findByIdAndTenantId(ArtifactId i, TenantId t) { return Optional.empty(); }
        public List<Artifact> findByDocumentIdAndTenantId(DocumentId d, TenantId t) { return List.of(); }
    }
    private static final class NoopStorage implements StoragePort {
        public ArtifactStorageReference store(TenantId t, String p, InputStream i, long s, String m) { return ArtifactStorageReference.local(p); }
        public InputStream retrieve(ArtifactStorageReference r) { return InputStream.nullInputStream(); }
        public void delete(ArtifactStorageReference r) { }
    }
}
