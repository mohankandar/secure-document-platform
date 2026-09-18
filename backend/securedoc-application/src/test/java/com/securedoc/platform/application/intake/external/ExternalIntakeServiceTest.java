package com.securedoc.platform.application.intake.external;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.IntakeConflictException;
import com.securedoc.platform.application.exception.ValidationException;
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

    @Test
    void scannerIntakeIsOneConsolidatedPdfAndUsesScannerPackageSource() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-10T12:00:00Z"));
        TenantId tenant = TenantId.generate();
        ActorId actor = ActorId.generate();
        AuthenticatedPrincipal principal = AuthenticatedPrincipal.of(actor, tenant, "scanner-client", Set.of());
        AuditService auditService = new AuditService(record -> { });
        FakeConnectionRepository connections = new FakeConnectionRepository();
        connections.save(IntegrationConnection.create(tenant, "Scanner", null, IntegrationPurpose.INGESTION_SOURCE,
                IntegrationDirection.INBOUND, IntegrationProviderType.SCANNER, IntegrationAuthenticationType.NONE,
                "scanner-client", null, null, null, 60, 2, 24, 1, 10_000_000L, 100,
                IntegrationAcknowledgementMode.NONE, true, clock.instant()));
        IntegrationConnectionService connectionService = new IntegrationConnectionService(connections, auditService);
        FakeExternalIntakeRepository intakes = new FakeExternalIntakeRepository();
        FakePackageRepository packages = new FakePackageRepository();
        List<CreatePackageCommand> commands = new ArrayList<>();
        CreatePackageUseCase create = (command, p) -> {
            commands.add(command);
            DocumentPackage pkg = DocumentPackage.create(p.tenantId(), command.packageType(), p.actorId(), PackageMetadata.of(command.metadata()));
            packages.save(pkg);
            return CreatePackageResult.of(pkg.id(), BusinessRecordId.generate(), pkg.status(), pkg.createdAt());
        };
        ChecksumPort checksum = input -> Checksum.sha256("a".repeat(64));
        PdfIntakeValidationPort pdf = input -> new PdfIntakeValidationPort.PdfInfo(1);
        ExternalIntakeMaintenanceService maintenance = new ExternalIntakeMaintenanceService(intakes, packages,
                new EmptyArtifactRepository(), new NoopStorage(), auditService, clock);
        ExternalIntakeService service = new ExternalIntakeService(connectionService, intakes, packages, create,
                (command, p) -> { throw new AssertionError("upload not expected"); },
                (command, p) -> { throw new AssertionError("submit not expected"); },
                command -> { throw new AssertionError("dispatch not expected"); }, checksum, pdf, auditService, maintenance, clock);

        String submissionId = UUID.randomUUID().toString();
        String sourceDocumentId = UUID.randomUUID().toString();
        Map<String, String> scannerMetadata = new LinkedHashMap<>();
        scannerMetadata.put("scannerCaptureMode", "SIMULATOR");
        scannerMetadata.put("bridgeId", "BRIDGE-LAS-01");
        scannerMetadata.put("siteId", "LAS-01");
        scannerMetadata.put("scannerId", "SCANNER-01");
        scannerMetadata.put("scanSessionId", UUID.randomUUID().toString());
        scannerMetadata.put("scanStartedAt", "2026-09-10T11:59:00Z");
        scannerMetadata.put("scanCompletedAt", "2026-09-10T12:00:00Z");
        scannerMetadata.put("scannerSubmittedAt", "2026-09-10T12:00:01Z");
        scannerMetadata.put("sourceSubmissionId", submissionId);
        scannerMetadata.put("sourceDocumentId", sourceDocumentId);
        scannerMetadata.put("sourceFileSizeBytes", "4727");
        scannerMetadata.put("sourceSha256", "a".repeat(64));
        PushIntakeCreateInput input = new PushIntakeCreateInput(submissionId, "1",
                Map.of("contract_number", "000900009901"), scannerMetadata,
                List.of(new PushIntakeCreateInput.ManifestDocumentInput(sourceDocumentId, "scan.pdf", "a".repeat(64))));
        service.createScannerIntake(input, principal, CorrelationId.generate());

        assertEquals(1, commands.size());
        assertEquals(PackageType.SCANNER_INTAKE, commands.get(0).packageType());
        assertEquals("SCANNER", commands.get(0).metadata().get("intakeChannel"));
        assertEquals("BRIDGE-LAS-01", commands.get(0).metadata().get("bridgeId"));
        assertEquals("LAS-01", commands.get(0).metadata().get("siteId"));
        assertEquals(sourceDocumentId, commands.get(0).metadata().get("sourceDocumentId"));
        assertEquals(submissionId, commands.get(0).metadata().get("sourceSubmissionId"));

        PushIntakeCreateInput invalid = new PushIntakeCreateInput(UUID.randomUUID().toString(), "1", Map.of(), Map.of(), List.of(
                new PushIntakeCreateInput.ManifestDocumentInput("DOC-A", "a.pdf", null),
                new PushIntakeCreateInput.ManifestDocumentInput("DOC-B", "b.pdf", null)));
        assertThrows(ValidationException.class, () -> service.createScannerIntake(invalid, principal, CorrelationId.generate()));
    }

    @Test
    void scannerDocumentReceiptConvergesIntoCommonSubmitAndProcessingDispatcher() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-10T12:00:00Z"));
        TenantId tenant = TenantId.generate();
        ActorId actor = ActorId.generate();
        AuthenticatedPrincipal principal = AuthenticatedPrincipal.of(actor, tenant, "scanner-client", Set.of());
        AuditService auditService = new AuditService(record -> { });
        FakeConnectionRepository connections = new FakeConnectionRepository();
        connections.save(IntegrationConnection.create(tenant, "Scanner", null, IntegrationPurpose.INGESTION_SOURCE,
                IntegrationDirection.INBOUND, IntegrationProviderType.SCANNER, IntegrationAuthenticationType.NONE,
                "scanner-client", null, null, null, 60, 2, 24, 1, 10_000_000L, 100,
                IntegrationAcknowledgementMode.NONE, true, clock.instant()));
        IntegrationConnectionService connectionService = new IntegrationConnectionService(connections, auditService);
        FakeExternalIntakeRepository intakes = new FakeExternalIntakeRepository();
        FakePackageRepository packages = new FakePackageRepository();
        CreatePackageUseCase create = (command, p) -> {
            DocumentPackage pkg = DocumentPackage.create(p.tenantId(), command.packageType(), p.actorId(), PackageMetadata.of(command.metadata()));
            packages.save(pkg);
            return CreatePackageResult.of(pkg.id(), BusinessRecordId.generate(), pkg.status(), pkg.createdAt());
        };
        int[] uploads = {0};
        int[] submits = {0};
        int[] dispatches = {0};
        UploadDocumentUseCase upload = (command, p) -> {
            uploads[0]++;
            return UploadDocumentResult.of(DocumentId.generate(), ArtifactId.generate(), DocumentStatus.UPLOADED);
        };
        SubmitPackageUseCase submit = (command, p) -> submits[0]++;
        PackageProcessingDispatcher dispatcher = command -> dispatches[0]++;
        ChecksumPort checksum = input -> Checksum.sha256("a".repeat(64));
        PdfIntakeValidationPort pdf = input -> new PdfIntakeValidationPort.PdfInfo(1);
        ExternalIntakeMaintenanceService maintenance = new ExternalIntakeMaintenanceService(intakes, packages,
                new EmptyArtifactRepository(), new NoopStorage(), auditService, clock);
        ExternalIntakeService service = new ExternalIntakeService(connectionService, intakes, packages, create, upload,
                submit, dispatcher, checksum, pdf, auditService, maintenance, clock);

        String submissionId = UUID.randomUUID().toString();
        String sourceDocumentId = UUID.randomUUID().toString();
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("scannerCaptureMode", "SIMULATOR");
        metadata.put("bridgeId", "BRIDGE-LAS-01");
        metadata.put("siteId", "LAS-01");
        metadata.put("scannerId", "SCANNER-01");
        metadata.put("scanSessionId", UUID.randomUUID().toString());
        metadata.put("scanStartedAt", "2026-09-10T11:59:00Z");
        metadata.put("scanCompletedAt", "2026-09-10T12:00:00Z");
        metadata.put("scannerSubmittedAt", "2026-09-10T12:00:01Z");
        metadata.put("sourceFileSizeBytes", "23");
        PushIntakeCreateInput input = new PushIntakeCreateInput(submissionId, "1",
                Map.of("contract_number", "000900009901"), metadata,
                List.of(new PushIntakeCreateInput.ManifestDocumentInput(sourceDocumentId, "scan.pdf", "a".repeat(64))));

        ExternalIntakeSnapshot created = service.createScannerIntake(input, principal, CorrelationId.generate());
        UploadContent content = new UploadContent() {
            private final byte[] bytes = "%PDF-1.4\nscanner\n%%EOF\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            @Override public InputStream openStream() { return new java.io.ByteArrayInputStream(bytes); }
            @Override public long contentLength() { return bytes.length; }
        };
        ExternalDocumentReceipt receipt = service.receiveScannerDocument(PackageId.of(created.packageId()), sourceDocumentId,
                submissionId + ":" + sourceDocumentId, "a".repeat(64), "application/pdf", content,
                principal, CorrelationId.generate());

        assertEquals(ExternalIntakeStatus.ACCEPTED_FOR_PROCESSING, receipt.intakeStatus());
        assertEquals(1, uploads[0]);
        assertEquals(1, submits[0]);
        assertEquals(1, dispatches[0]);
        DocumentPackage receivedPackage = packages.findByIdAndTenantId(PackageId.of(created.packageId()), tenant).orElseThrow();
        assertEquals("23", receivedPackage.metadata().get("sourceFileSizeBytes").orElseThrow());
        assertEquals("1", receivedPackage.metadata().get("sourcePageCount").orElseThrow());
        assertEquals("a".repeat(64), receivedPackage.metadata().get("sourceSha256").orElseThrow());
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
        public Optional<IntegrationConnection> findEnabledScannerSourceByTenantIdAndClientPrincipal(TenantId t, String p) { return values.stream().filter(v -> v.tenantId().equals(t) && v.enabled() && v.providerType() == IntegrationProviderType.SCANNER && p.equals(v.clientPrincipal())).findFirst(); }
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
