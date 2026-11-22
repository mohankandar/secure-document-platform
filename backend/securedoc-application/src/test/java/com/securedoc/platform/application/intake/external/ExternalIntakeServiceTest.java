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
    /** Task 0017 A2: a push client's records carry its configured connection name, never its client principal. */
    @Test
    void pushClientRecordsTheConnectionNameAsDisplayName() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-01T00:00:00Z"));
        TenantId tenant = TenantId.generate();
        ActorId actor = ActorId.generate();
        AuthenticatedPrincipal principal = AuthenticatedPrincipal.of(actor, tenant, "los-client-principal", Set.of());
        List<AuditRecord> audits = new ArrayList<>();
        com.securedoc.platform.application.audit.RequestActorDisplayName names =
                new com.securedoc.platform.application.audit.RequestActorDisplayName();
        AuditService auditService = new AuditService(audits::add, names);
        FakeConnectionRepository connections = new FakeConnectionRepository();
        connections.save(IntegrationConnection.create(tenant, "Loan origination", null,
                IntegrationPurpose.INGESTION_SOURCE, IntegrationDirection.INBOUND, IntegrationProviderType.PUSH_API,
                IntegrationAuthenticationType.JWT, "los-client-principal", null, null, null, 60, 2, 24,
                10, 10_000_000L, 100, IntegrationAcknowledgementMode.NONE, true, clock.instant()));
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
        ExternalIntakeMaintenanceService maintenance = new ExternalIntakeMaintenanceService(intakes, packages,
                new EmptyArtifactRepository(), new NoopStorage(), auditService, clock);
        ExternalIntakeService service = new ExternalIntakeService(connectionService, intakes, packages, create, upload,
                submit, dispatcher, input -> Checksum.sha256("a".repeat(64)), input -> new PdfIntakeValidationPort.PdfInfo(1),
                auditService, maintenance, names, clock);

        try {
            service.createPushIntake(new PushIntakeCreateInput("SRC-NAME-1", "1", Map.of("contractNumber", "C-1"),
                    Map.of(), List.of(new PushIntakeCreateInput.ManifestDocumentInput("DOC-1", "one.pdf", null))),
                    principal, CorrelationId.generate());
            AuditRecord created = audits.stream()
                    .filter(r -> r.action() == com.securedoc.platform.domain.audit.AuditAction.EXTERNAL_INTAKE_CREATED)
                    .findFirst().orElseThrow();
            assertEquals("Push API: Loan origination", created.actorDisplayName());
            assertTrue(audits.stream().noneMatch(r -> r.actorDisplayName() != null
                    && r.actorDisplayName().contains("los-client-principal")), "never the client principal");
        } finally {
            names.clear();
        }
    }

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

    // ---------- Task 0021: sensitivity levels at intake ----------

    /** A push service whose create/upload steps are recorded, so the levels handed on can be checked. */
    private static final class LevelHarness {
        final TenantId tenant = TenantId.generate();
        final AuthenticatedPrincipal principal = AuthenticatedPrincipal.of(ActorId.generate(), tenant, "los-client", Set.of());
        final List<AuditRecord> audits = new ArrayList<>();
        final List<CreatePackageCommand> creates = new ArrayList<>();
        final List<UploadDocumentCommand> uploads = new ArrayList<>();
        final FakePackageRepository packages = new FakePackageRepository();
        final ExternalIntakeService service;

        LevelHarness(IntegrationProviderType provider) {
            this(provider, provider == IntegrationProviderType.SCANNER ? 1 : 10, 100, 1);
        }

        /** Task 0038: a source whose own limits are above the case limits, and a PDF of {@code pdfPages} pages. */
        LevelHarness(IntegrationProviderType provider, int maxDocuments, int maxPages, int pdfPages) {
            MutableClock clock = new MutableClock(Instant.parse("2026-09-10T12:00:00Z"));
            AuditService auditService = new AuditService(audits::add);
            FakeConnectionRepository connections = new FakeConnectionRepository();
            connections.save(IntegrationConnection.create(tenant, "Source", null, IntegrationPurpose.INGESTION_SOURCE,
                    IntegrationDirection.INBOUND, provider, provider == IntegrationProviderType.SCANNER
                            ? IntegrationAuthenticationType.NONE : IntegrationAuthenticationType.JWT,
                    "los-client", null, null, null, 60, 2, 24, maxDocuments,
                    10_000_000L, maxPages,
                    IntegrationAcknowledgementMode.NONE, true, clock.instant()));
            FakeExternalIntakeRepository intakes = new FakeExternalIntakeRepository();
            CreatePackageUseCase create = (command, p) -> {
                creates.add(command);
                DocumentPackage pkg = DocumentPackage.createIntake(p.tenantId(), command.packageType(), null, null, null,
                        p.actorId(), PackageMetadata.of(command.metadata()), command.caseSensitivity().level());
                packages.save(pkg);
                return CreatePackageResult.of(pkg.id(), BusinessRecordId.generate(), pkg.status(), pkg.createdAt());
            };
            UploadDocumentUseCase upload = (command, p) -> {
                uploads.add(command);
                return UploadDocumentResult.of(DocumentId.generate(), ArtifactId.generate(), DocumentStatus.UPLOADED);
            };
            ExternalIntakeMaintenanceService maintenance = new ExternalIntakeMaintenanceService(intakes, packages,
                    new EmptyArtifactRepository(), new NoopStorage(), auditService, clock);
            service = new ExternalIntakeService(new IntegrationConnectionService(connections, auditService), intakes,
                    packages, create, upload, (command, p) -> { }, command -> { },
                    input -> Checksum.sha256("a".repeat(64)), input -> new PdfIntakeValidationPort.PdfInfo(pdfPages),
                    auditService, maintenance, clock);
        }

        void receive(UUID packageId, String externalId) {
            UploadContent content = new UploadContent() {
                private final byte[] bytes = "%PDF-1.4\nx\n%%EOF\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                @Override public InputStream openStream() { return new java.io.ByteArrayInputStream(bytes); }
                @Override public long contentLength() { return bytes.length; }
            };
            service.receivePushDocument(PackageId.of(packageId), externalId, "key-" + externalId, "a".repeat(64),
                    "application/pdf", content, principal, CorrelationId.generate());
        }

        long unknownLevelAudits() {
            return audits.stream().filter(r -> r.action()
                    == com.securedoc.platform.domain.audit.AuditAction.INTAKE_SENSITIVITY_LEVEL_UNKNOWN).count();
        }
    }

    private static PushIntakeCreateInput.ManifestDocumentInput doc(String id, String level) {
        return new PushIntakeCreateInput.ManifestDocumentInput(id, id + ".pdf", null, level);
    }

    /* Task 0023 B2 (#49, #55): say what is wrong without the value, and before the case is created. */

    @Test
    void aRepeatedDocumentIdIsRefusedBeforeTheCaseIsMadeWithoutEchoingIt() {
        LevelHarness h = new LevelHarness(IntegrationProviderType.PUSH_API);
        ValidationException refused = assertThrows(ValidationException.class, () -> h.service.createPushIntake(
                new PushIntakeCreateInput("SRC-D1", "1", Map.of("contractNumber", "C-1"), Map.of(),
                        List.of(doc("JANE-DOE-0042", null), doc("JANE-DOE-0042", null))), h.principal, CorrelationId.generate()));
        assertEquals("Document manifest lists the same externalSourceDocumentId more than once", refused.getMessage());
        assertTrue(h.creates.isEmpty(), "no case is created for a refused manifest");
    }

    @Test
    void aFileNameWithAPathIsRefusedBeforeTheCaseIsMadeWithoutEchoingIt() {
        LevelHarness h = new LevelHarness(IntegrationProviderType.PUSH_API);
        ValidationException refused = assertThrows(ValidationException.class, () -> h.service.createPushIntake(
                new PushIntakeCreateInput("SRC-D2", "1", Map.of("contractNumber", "C-1"), Map.of(),
                        List.of(new PushIntakeCreateInput.ManifestDocumentInput("DOC-1", "clients/Jane Doe.pdf", null, null))),
                h.principal, CorrelationId.generate()));
        assertEquals("Document manifest fileName is not valid: FileName must not contain path separators", refused.getMessage());
        assertFalse(refused.getMessage().contains("Jane"));
        assertTrue(h.creates.isEmpty());
    }

    /* Task 0038: the case limits hold for push too, even when the source allows more. */

    @Test
    void aManifestOfMoreThanFiftyFilesIsRefusedBeforeTheCaseIsMade() {
        LevelHarness h = new LevelHarness(IntegrationProviderType.PUSH_API, 100, 1000, 1);
        List<PushIntakeCreateInput.ManifestDocumentInput> docs = new ArrayList<>();
        for (int i = 0; i < 51; i++) docs.add(doc("DOC-" + i, null));
        ValidationException refused = assertThrows(ValidationException.class, () -> h.service.createPushIntake(
                new PushIntakeCreateInput("SRC-L1", "1", Map.of("contractNumber", "C-1"), Map.of(), docs),
                h.principal, CorrelationId.generate()));
        assertEquals(com.securedoc.platform.domain.error.LvErrorCode.INT_029, refused.lvCode());
        assertTrue(h.creates.isEmpty(), "no case is created");
        // Fifty is taken.
        h.service.createPushIntake(new PushIntakeCreateInput("SRC-L2", "1", Map.of("contractNumber", "C-1"), Map.of(),
                docs.subList(0, 50)), h.principal, CorrelationId.generate());
        assertEquals(1, h.creates.size());
    }

    @Test
    void aPushedPdfOfMoreThanFiveHundredPagesIsRefusedAndNotTakenIn() {
        LevelHarness over = new LevelHarness(IntegrationProviderType.PUSH_API, 10, 1000, 501);
        ExternalIntakeSnapshot created = over.service.createPushIntake(new PushIntakeCreateInput("SRC-L3", "1",
                Map.of("contractNumber", "C-1"), Map.of(), List.of(doc("DOC-1", null))), over.principal, CorrelationId.generate());
        ValidationException refused = assertThrows(ValidationException.class, () -> over.receive(created.packageId(), "DOC-1"));
        assertEquals(com.securedoc.platform.domain.error.LvErrorCode.INT_028, refused.lvCode());
        assertTrue(over.uploads.isEmpty(), "nothing taken in");

        LevelHarness atLimit = new LevelHarness(IntegrationProviderType.PUSH_API, 10, 1000, 500);
        ExternalIntakeSnapshot ok = atLimit.service.createPushIntake(new PushIntakeCreateInput("SRC-L4", "1",
                Map.of("contractNumber", "C-1"), Map.of(), List.of(doc("DOC-1", null))), atLimit.principal, CorrelationId.generate());
        atLimit.receive(ok.packageId(), "DOC-1");
        assertEquals(1, atLimit.uploads.size(), "500 pages is taken in");
    }

    /* Task 0023 D3 (#22) and #357. */

    @Test
    void anEmptyFileIsSaidToBeEmpty() {
        LevelHarness h = new LevelHarness(IntegrationProviderType.PUSH_API);
        ExternalIntakeSnapshot created = h.service.createPushIntake(new PushIntakeCreateInput("SRC-E1", "1",
                Map.of("contractNumber", "C-1"), Map.of(), List.of(doc("DOC-1", null))), h.principal, CorrelationId.generate());
        ValidationException refused = assertThrows(ValidationException.class, () -> h.service.receivePushDocument(
                PackageId.of(created.packageId()), "DOC-1", "key-1", "a".repeat(64), "application/pdf",
                bytes(new byte[0]), h.principal, CorrelationId.generate()));
        assertEquals("The file is empty", refused.getMessage());
    }

    @Test
    void theServerFailingToReadItsOwnCopyIsAStorageFaultNotTheClientsMistake() {
        LevelHarness h = new LevelHarness(IntegrationProviderType.PUSH_API);
        ExternalIntakeSnapshot created = h.service.createPushIntake(new PushIntakeCreateInput("SRC-E2", "1",
                Map.of("contractNumber", "C-1"), Map.of(), List.of(doc("DOC-1", null))), h.principal, CorrelationId.generate());
        UploadContent unreadable = new UploadContent() {
            @Override public InputStream openStream() { throw new java.io.UncheckedIOException(new java.io.IOException("C:\\spool\\x.tmp")); }
            @Override public long contentLength() { return 10; }
        };
        var failure = assertThrows(com.securedoc.platform.application.exception.TechnicalDependencyException.class, () ->
                h.service.receivePushDocument(PackageId.of(created.packageId()), "DOC-1", "key-1", "a".repeat(64),
                        "application/pdf", unreadable, h.principal, CorrelationId.generate()));
        assertEquals(com.securedoc.platform.application.exception.TechnicalFailureCode.STORAGE_IO, failure.failureCode());
        assertTrue(failure.retryable());
        assertFalse(failure.getMessage().contains("spool"));
    }

    /* Task 0023 A4: no value sent and an unknown value give Standard on the scanner and polling paths too. */

    @Test
    void scannerIntakeWithNoLevelOrAnUnknownLevelIsStandard() {
        for (String sent : Arrays.asList(null, "TOP_SECRET", " confidential ")) {
            LevelHarness h = new LevelHarness(IntegrationProviderType.SCANNER);
            ExternalIntakeSnapshot created = h.service.createScannerIntake(new PushIntakeCreateInput(UUID.randomUUID().toString(), "1",
                    Map.of("contract_number", "1"), scannerMetadata(), List.of(new PushIntakeCreateInput.ManifestDocumentInput(
                            UUID.randomUUID().toString(), "scan.pdf", "a".repeat(64))), false, sent), h.principal, CorrelationId.generate());
            assertEquals(SensitivityLevel.STANDARD, h.creates.get(0).caseSensitivity().level(), String.valueOf(sent));
            assertEquals(sent != null, h.creates.get(0).caseSensitivity().unknown(), "an unknown value carries LV-INT-005");
            assertEquals(sent == null ? 0 : 1, created.warnings().size());
        }
    }

    @Test
    void pollingIntakeWithNoLevelOrAnUnknownLevelIsStandard() {
        LevelHarness h = new LevelHarness(IntegrationProviderType.PUSH_API);
        IntegrationConnection polling = IntegrationConnection.create(h.tenant, "Polling", null,
                IntegrationPurpose.INGESTION_SOURCE, IntegrationDirection.OUTBOUND, IntegrationProviderType.HTTP_API,
                IntegrationAuthenticationType.NONE, null, "https://source.example.test", null, null,
                30, 0, 24, 10, 10_000_000L, 100,
                IntegrationPollingScheduleType.INTERVAL, 60, null, null, 10,
                IntegrationAcknowledgementMode.SOURCE_PICKUP_ACK, true, Instant.parse("2026-09-10T11:00:00Z"));
        ExternalIntakeSnapshot created = h.service.createPollingIntake(new PushIntakeCreateInput("SRC-P1", "1",
                Map.of("contractNumber", "C-1"), Map.of(), List.of(doc("DOC-1", null), doc("DOC-2", "SECRET")), false, null),
                polling, h.principal, CorrelationId.generate());
        assertEquals(SensitivityLevel.Received.standard(), h.creates.get(0).caseSensitivity(), "nothing sent: Standard, no warning");
        assertEquals(1, created.warnings().size(), "the unknown document level is warned");
        ExternalIntakeSnapshot unknownCase = h.service.createPollingIntake(new PushIntakeCreateInput("SRC-P2", "1",
                Map.of("contractNumber", "C-1"), Map.of(), List.of(doc("DOC-1", null)), false, "Restricted"),
                polling, h.principal, CorrelationId.generate());
        assertEquals(SensitivityLevel.STANDARD, h.creates.get(1).caseSensitivity().level());
        assertTrue(h.creates.get(1).caseSensitivity().unknown());
        assertEquals("LV-INT-005", unknownCase.warnings().get(0).code());
    }

    private static Map<String, String> scannerMetadata() {
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
        return metadata;
    }

    private static UploadContent bytes(byte[] bytes) {
        return new UploadContent() {
            @Override public InputStream openStream() { return new java.io.ByteArrayInputStream(bytes); }
            @Override public long contentLength() { return bytes.length; }
        };
    }

    @Test
    void pushIntakeKeepsTheLevelsSentAndNothingSentIsStandard() {
        LevelHarness h = new LevelHarness(IntegrationProviderType.PUSH_API);
        ExternalIntakeSnapshot created = h.service.createPushIntake(new PushIntakeCreateInput("SRC-L1", "1",
                Map.of("contractNumber", "C-1"), Map.of(),
                List.of(doc("DOC-1", "HIGHLY_CONFIDENTIAL"), doc("DOC-2", null)), false, "CONFIDENTIAL"),
                h.principal, CorrelationId.generate());

        assertEquals(SensitivityLevel.CONFIDENTIAL, h.creates.get(0).caseSensitivity().level());
        assertFalse(h.creates.get(0).caseSensitivity().unknown());
        assertTrue(created.warnings().isEmpty());
        assertEquals(0, h.unknownLevelAudits());

        h.receive(created.packageId(), "DOC-1");
        h.receive(created.packageId(), "DOC-2");
        assertEquals(SensitivityLevel.HIGHLY_CONFIDENTIAL, h.uploads.get(0).sensitivityLevel());
        assertEquals(SensitivityLevel.STANDARD, h.uploads.get(1).sensitivityLevel(), "nothing sent means Standard");
    }

    @Test
    void noLevelAtAllIsStandardEverywhere() {
        LevelHarness h = new LevelHarness(IntegrationProviderType.PUSH_API);
        ExternalIntakeSnapshot created = h.service.createPushIntake(new PushIntakeCreateInput("SRC-L0", "1",
                Map.of("contractNumber", "C-1"), Map.of(), List.of(doc("DOC-1", null))), h.principal, CorrelationId.generate());
        assertEquals(SensitivityLevel.Received.standard(), h.creates.get(0).caseSensitivity());
        h.receive(created.packageId(), "DOC-1");
        assertEquals(SensitivityLevel.STANDARD, h.uploads.get(0).sensitivityLevel());
        assertTrue(created.warnings().isEmpty());
    }

    @Test
    void anUnknownLevelIsTakenAsStandardWithTheWarningAndNeverRefused() {
        LevelHarness h = new LevelHarness(IntegrationProviderType.PUSH_API);
        PushIntakeCreateInput input = new PushIntakeCreateInput("SRC-L2", "1", Map.of("contractNumber", "C-1"), Map.of(),
                List.of(doc("DOC-1", "SECRET"), doc("DOC-2", "CONFIDENTIAL")), false, "confidential");
        ExternalIntakeSnapshot created = h.service.createPushIntake(input, h.principal, CorrelationId.generate());

        assertEquals(SensitivityLevel.STANDARD, h.creates.get(0).caseSensitivity().level());
        assertTrue(h.creates.get(0).caseSensitivity().unknown(), "CreatePackageService records the case warning");
        assertEquals(2, created.warnings().size());
        assertEquals("LV-INT-005", created.warnings().get(0).code());
        assertEquals("CASE", created.warnings().get(0).scope());
        assertEquals("DOCUMENT", created.warnings().get(1).scope());
        assertEquals("DOC-1", created.warnings().get(1).externalSourceDocumentId());
        assertEquals("Unknown sensitivity level, taken as Standard", created.warnings().get(1).message());

        // The document warning is audited here, with the code only, never the value sent.
        assertEquals(1, h.unknownLevelAudits());
        AuditRecord warning = h.audits.stream().filter(r -> r.action()
                == com.securedoc.platform.domain.audit.AuditAction.INTAKE_SENSITIVITY_LEVEL_UNKNOWN).findFirst().orElseThrow();
        assertEquals("code=LV-INT-005,scope=DOCUMENT", warning.detail());
        assertTrue(h.audits.stream().noneMatch(r -> r.detail() != null
                && (r.detail().contains("SECRET") || r.detail().contains("confidential"))));

        h.receive(created.packageId(), "DOC-1");
        h.receive(created.packageId(), "DOC-2");
        assertEquals(SensitivityLevel.STANDARD, h.uploads.get(0).sensitivityLevel());
        assertEquals(SensitivityLevel.CONFIDENTIAL, h.uploads.get(1).sensitivityLevel());

        // A replay of the same request answers with the same warnings and records nothing new.
        ExternalIntakeSnapshot replay = h.service.createPushIntake(input, h.principal, CorrelationId.generate());
        assertEquals(created.packageId(), replay.packageId());
        assertEquals(2, replay.warnings().size());
        assertEquals(1, h.unknownLevelAudits());
    }

    @Test
    void aDifferentLevelOnTheSameRevisionIsAChangedRequest() {
        LevelHarness h = new LevelHarness(IntegrationProviderType.PUSH_API);
        h.service.createPushIntake(new PushIntakeCreateInput("SRC-L3", "1", Map.of("contractNumber", "C-1"), Map.of(),
                List.of(doc("DOC-1", null)), false, "CONFIDENTIAL"), h.principal, CorrelationId.generate());
        IntakeConflictException conflict = assertThrows(IntakeConflictException.class, () ->
                h.service.createPushIntake(new PushIntakeCreateInput("SRC-L3", "1", Map.of("contractNumber", "C-1"),
                        Map.of(), List.of(doc("DOC-1", null)), false, "HIGHLY_CONFIDENTIAL"), h.principal,
                        CorrelationId.generate()));
        assertEquals("SOURCE_REVISION_REUSED", conflict.errorCode());
    }

    @Test
    void scannerIntakeCarriesTheCaseLevelChosenOnNewCase() {
        LevelHarness h = new LevelHarness(IntegrationProviderType.SCANNER);
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
        h.service.createScannerIntake(new PushIntakeCreateInput(submissionId, "1", Map.of("contract_number", "1"),
                metadata, List.of(new PushIntakeCreateInput.ManifestDocumentInput(sourceDocumentId, "scan.pdf", "a".repeat(64))),
                false, "HIGHLY_CONFIDENTIAL"), h.principal, CorrelationId.generate());
        assertEquals(SensitivityLevel.HIGHLY_CONFIDENTIAL, h.creates.get(0).caseSensitivity().level());
        assertEquals(PackageType.SCANNER_INTAKE, h.creates.get(0).packageType());
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
