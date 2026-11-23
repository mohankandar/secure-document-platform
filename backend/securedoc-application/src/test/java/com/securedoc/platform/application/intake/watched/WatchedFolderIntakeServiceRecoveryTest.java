package com.securedoc.platform.application.intake.watched;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.intake.command.*;
import com.securedoc.platform.application.intake.model.IntakeRejectionCode;
import com.securedoc.platform.application.port.in.*;
import com.securedoc.platform.application.port.out.*;
import com.securedoc.platform.application.processing.command.TriggerPackageProcessingCommand;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.document.*;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.FileName;
import com.securedoc.platform.domain.vo.MimeType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class WatchedFolderIntakeServiceRecoveryTest {

    private final TenantId tenantId = TenantId.of(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private final ActorId actorId = ActorId.of(UUID.fromString("22222222-2222-2222-2222-222222222222"));
    private final AuthenticatedPrincipal principal = AuthenticatedPrincipal.of(
            actorId, tenantId, "watched-folder-system", Set.of("PLATFORM_OPERATIONS_ADMIN"));
    private final CorrelationId correlationId = CorrelationId.generate();

    @Test
    void resumesCreatedPackageWithZeroDocumentsInsteadOfFalseDuplicate() {
        var candidate = candidate("case-zero", doc("one.pdf", "one"));
        var repository = new MemoryPackageRepository(existingPackage("case-zero"));
        var harness = harness(repository, candidate, false);

        ScanWatchedFolderResult result = harness.service.scan(principal, correlationId);

        assertEquals(1, result.accepted());
        assertEquals(0, result.duplicates());
        assertEquals(0, result.rejected());
        assertEquals(PackageStatus.SUBMITTED, repository.current.status());
        assertEquals(1, repository.current.documentCount());
        assertEquals(1, harness.uploads());
        assertEquals(1, harness.submits());
        assertEquals(1, harness.triggers());
        assertEquals(IntakeDisposition.ACCEPTED, harness.source.disposition);
        assertEquals(IntakeObservationStatus.ACCEPTED, harness.observations.latest.status());
        assertEquals(repository.current.id(), harness.observations.latest.packageId());
    }

    @Test
    void resumesPartiallyUploadedPackageWithoutDuplicatingPersistedDocument() {
        var first = doc("one.pdf", "one");
        var second = doc("two.pdf", "two");
        var candidate = candidate("case-partial", first, second);
        DocumentPackage existing = existingPackage("case-partial");
        existing.addDocument(persisted(existing.id(), first));
        var repository = new MemoryPackageRepository(existing);
        var harness = harness(repository, candidate, false);

        ScanWatchedFolderResult result = harness.service.scan(principal, correlationId);

        assertEquals(1, result.accepted());
        assertEquals(1, harness.uploads(), "only the missing document should be uploaded");
        assertEquals(2, repository.current.documentCount());
        assertEquals(PackageStatus.SUBMITTED, repository.current.status());
        assertEquals(Set.of("one.pdf", "two.pdf"), repository.current.documents().stream()
                .map(d -> d.fileName().value()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void finalizesPostSubmitRecoveryAsAcceptedWithoutReuploadOrResubmit() {
        var first = doc("one.pdf", "one");
        var candidate = candidate("case-submitted", first);
        DocumentPackage existing = existingPackage("case-submitted");
        existing.addDocument(persisted(existing.id(), first));
        existing.submit();
        var repository = new MemoryPackageRepository(existing);
        var harness = harness(repository, candidate, false);

        ScanWatchedFolderResult result = harness.service.scan(principal, correlationId);

        assertEquals(1, result.accepted());
        assertEquals(0, result.duplicates());
        assertEquals(0, harness.uploads());
        assertEquals(0, harness.submits());
        assertEquals(1, harness.triggers());
        assertEquals(IntakeObservationStatus.ACCEPTED, harness.observations.latest.status());
    }

    @Test
    void conflictingPersistedDocumentFailsClosedInsteadOfArchivingAsDuplicate() {
        var candidateDoc = doc("one.pdf", "expected");
        var candidate = candidate("case-conflict", candidateDoc);
        DocumentPackage existing = existingPackage("case-conflict");
        existing.addDocument(persisted(existing.id(), doc("one.pdf", "different")));
        var repository = new MemoryPackageRepository(existing);
        var harness = harness(repository, candidate, false);

        ScanWatchedFolderResult result = harness.service.scan(principal, correlationId);

        assertEquals(1, result.rejected());
        assertEquals(0, result.duplicates());
        assertEquals(PackageStatus.CREATED, repository.current.status());
        assertEquals(IntakeDisposition.REJECTED, harness.source.disposition);
        assertEquals(IntakeObservationStatus.REJECTED, harness.observations.latest.status());
        assertEquals(IntakeRejectionCode.RECOVERY_CONFLICT, harness.observations.latest.reasonCode(), "task 0023 C2");
    }

    @Test
    void uniqueFingerprintCreateRaceRereadsWinnerAndResumesSameSource() {
        var candidate = candidate("case-race", doc("one.pdf", "one"));
        var repository = new MemoryPackageRepository(null);
        var harness = harness(repository, candidate, true);

        ScanWatchedFolderResult result = harness.service.scan(principal, correlationId);

        assertEquals(1, result.accepted());
        assertEquals(0, result.duplicates());
        assertNotNull(repository.current);
        // Task 0023 B3: the case holds the drop reference, not the drop folder's name.
        assertEquals(WatchedFolderDropReference.of("case-race"), repository.current.sourceReference().orElseThrow());
        assertEquals(PackageStatus.SUBMITTED, repository.current.status());
        assertEquals(1, repository.current.documentCount());
    }

    @Test
    void sameFingerprintFromDifferentSourceRemainsNormalDuplicate() {
        var candidate = candidate("new-source", doc("one.pdf", "one"));
        var repository = new MemoryPackageRepository(existingPackage("original-source"));
        var harness = harness(repository, candidate, false);

        ScanWatchedFolderResult result = harness.service.scan(principal, correlationId);

        assertEquals(1, result.duplicates());
        assertEquals(0, result.accepted());
        assertEquals(IntakeDisposition.DUPLICATE, harness.source.disposition);
        assertEquals(IntakeObservationStatus.DUPLICATE, harness.observations.latest.status());
    }

    /** Task 0021: every file picked up from the folder gets the level Tenant IT set for it (default Standard). */
    @Test
    void everyFilePickedUpGetsTheFolderLevel() {
        for (SensitivityLevel folderLevel : SensitivityLevel.values()) {
            var candidate = candidate("case-level-" + folderLevel, doc("one.pdf", "one"), doc("two.pdf", "two"));
            var repository = new MemoryPackageRepository(null);
            List<CreatePackageCommand> creates = new ArrayList<>();
            List<SensitivityLevel> uploadLevels = new ArrayList<>();
            CreatePackageUseCase create = (command, p) -> {
                creates.add(command);
                DocumentPackage pkg = DocumentPackage.createIntake(tenantId, PackageType.FOLDER_INTAKE,
                        BusinessRecordId.generate(), command.intakeFingerprint(), command.sourceReference(), actorId,
                        PackageMetadata.empty(), command.caseSensitivity().level());
                repository.current = pkg;
                return CreatePackageResult.of(pkg.id(), BusinessRecordId.generate(), pkg.status(), pkg.createdAt());
            };
            UploadDocumentUseCase upload = (command, p) -> {
                uploadLevels.add(command.sensitivityLevel());
                DocumentPackage pkg = repository.findByIdAndTenantId(command.packageId(), tenantId).orElseThrow();
                Document document = persisted(pkg.id(), new WatchedFolderDocument(command.fileName(), command.mimeType(), command.content()));
                pkg.addDocument(document);
                return UploadDocumentResult.of(document.id(), ArtifactId.generate(), document.status());
            };
            SubmitPackageUseCase submit = (command, p) -> repository.current.submit();
            WatchedFolderIntakeService service = new WatchedFolderIntakeService(new SourceStub(candidate), new LeaseStub(),
                    new ObservationStub(), repository, create, upload, submit, command -> { }, new AuditService(r -> { }),
                    Duration.ofMinutes(15), Duration.ofSeconds(10), tenant -> folderLevel);

            assertEquals(1, service.scan(principal, correlationId).accepted());
            assertEquals(folderLevel, creates.get(0).caseSensitivity().level());
            assertFalse(creates.get(0).caseSensitivity().unknown());
            assertEquals(List.of(folderLevel, folderLevel), uploadLevels);
        }
    }

    @Test
    void withoutAFolderSettingFilesAreStandard() {
        WatchedFolderSettingsService settings = new WatchedFolderSettingsService(new MemorySettings(),
                new AuditService(r -> { }), null);
        assertEquals(SensitivityLevel.STANDARD, settings.sensitivityLevel(tenantId, WatchedFolderSettingsService.INSTALLED_FOLDER));
    }

    @Test
    void tenantItChangesTheFolderLevelAndItIsAuditedAsCodes() {
        List<com.securedoc.platform.domain.audit.AuditRecord> audits = new ArrayList<>();
        MemorySettings store = new MemorySettings();
        WatchedFolderSettingsService settings = new WatchedFolderSettingsService(store, new AuditService(audits::add), null);
        settings.changeSensitivityLevel(tenantId, actorId, WatchedFolderSettingsService.INSTALLED_FOLDER,
                SensitivityLevel.HIGHLY_CONFIDENTIAL, correlationId);
        assertEquals(SensitivityLevel.HIGHLY_CONFIDENTIAL,
                settings.sensitivityLevel(tenantId, WatchedFolderSettingsService.INSTALLED_FOLDER));
        assertEquals(SensitivityLevel.STANDARD, settings.sensitivityLevel(TenantId.generate(),
                WatchedFolderSettingsService.INSTALLED_FOLDER), "a setting belongs to one tenant");
        assertEquals(1, audits.size());
        assertEquals(com.securedoc.platform.domain.audit.AuditAction.WATCHED_FOLDER_SETTINGS_CHANGED, audits.get(0).action());
        assertEquals("sensitivityLevel=STANDARD->HIGHLY_CONFIDENTIAL", audits.get(0).detail());
    }

    private static final class MemorySettings implements WatchedFolderSettingsRepository {
        private final Map<String, SensitivityLevel> values = new HashMap<>();
        @Override public Optional<SensitivityLevel> findSensitivityLevel(TenantId tenantId, String folderKey) {
            return Optional.ofNullable(values.get(tenantId + "|" + folderKey));
        }
        @Override public void saveSensitivityLevel(TenantId tenantId, String folderKey, SensitivityLevel level,
                                                   ActorId updatedBy, Instant updatedAt) {
            values.put(tenantId + "|" + folderKey, level);
        }
    }

    /* Task 0023 B3: no drop folder name on screen, in audit records or in the case; the folder key plus a short id. */

    @Test
    void theObservationTheCaseAndTheAuditNameTheDropByReferenceNeverByFolderName() {
        String folderName = "Jane Doe - loan 42";
        Fresh fresh = fresh(candidate(folderName, doc("Jane Doe payslip.pdf", "one")), (command, p) -> null);

        assertEquals(1, fresh.service.scan(principal, correlationId).accepted());

        String expected = "installed/" + sha256Hex(folderName).substring(0, 12);
        assertEquals(expected, WatchedFolderDropReference.of(folderName));
        assertEquals(expected, fresh.observations.latest.sourceReference());
        assertEquals(expected, fresh.repository.current.sourceReference().orElseThrow());
        assertTrue(fresh.audits.stream().anyMatch(r -> r.detail() != null && r.detail().contains("drop=" + expected)));
        fresh.audits.forEach(r -> assertFalse(r.detail() != null && r.detail().contains("Jane"), r.action().name()));
    }

    @Test
    void aCaseMadeBeforeTheDropReferenceStillResumesUnderTheFolderName() {
        // existingPackage(...) holds the raw folder name, as cases made before task 0023 do.
        var candidate = candidate("case-legacy", doc("one.pdf", "one"));
        var repository = new MemoryPackageRepository(existingPackage("case-legacy"));
        var harness = harness(repository, candidate, false);
        assertEquals(1, harness.service.scan(principal, correlationId).accepted());
        assertEquals(WatchedFolderDropReference.of("case-legacy"), harness.observations.latest.sourceReference());
    }

    /* Task 0038: a drop with more files than a case holds is refused before a case is made. */

    @Test
    void aDropOfMoreThanFiftyFilesIsRefusedBeforeACaseIsMade() {
        WatchedFolderDocument[] files = new WatchedFolderDocument[51];
        for (int i = 0; i < files.length; i++) files[i] = doc("f" + i + ".pdf", "c" + i);
        Fresh fresh = fresh(candidate("drop-51", files), (command, p) -> null);

        assertEquals(1, fresh.service.scan(principal, correlationId).rejected());
        assertTrue(fresh.creates.isEmpty(), "no case is made");
        assertEquals(IntakeRejectionCode.INVALID_FILE, fresh.observations.latest.reasonCode());
        var rejected = fresh.audits.stream().filter(r -> r.action()
                == com.securedoc.platform.domain.audit.AuditAction.INTAKE_REJECTED).findFirst().orElseThrow();
        assertTrue(rejected.detail().startsWith("code=LV-INT-029, "), rejected.detail());

        Fresh fifty = fresh(candidate("drop-50", java.util.Arrays.copyOf(files, 50)), (command, p) -> null);
        assertEquals(1, fifty.service.scan(principal, correlationId).accepted(), "fifty files are taken in");
    }

    /* Task 0023 C2: the real reason for each refused drop, as a code in the observation and the audit. */

    @Test
    void eachRefusedDropRecordsItsRealReasonNotAnIoError() {
        record Case(RuntimeException thrown, IntakeRejectionCode reason, String causeCode) {}
        List<Case> cases = List.of(
                new Case(new com.securedoc.platform.application.exception.IntakeValidationException(
                        IntakeRejectionCode.UNKNOWN_IDENTIFIER, "Unknown identifier key Secret-Key"),
                        IntakeRejectionCode.UNKNOWN_IDENTIFIER, "INTAKE_UNKNOWN_IDENTIFIER"),
                new Case(new com.securedoc.platform.application.licensing.LicenseEnforcementException("LICENSE_EXPIRED",
                        "Tenant license has expired", com.securedoc.platform.domain.licensing.LicenseValidationStatus.EXPIRED),
                        IntakeRejectionCode.LICENSE_DENIED, "LICENSE_EXPIRED"),
                new Case(new com.securedoc.platform.application.exception.CapabilityNotEnabledException(
                        com.securedoc.platform.domain.tenant.Capability.DOCUMENT_UPLOAD),
                        IntakeRejectionCode.CAPABILITY_DISABLED, "CAPABILITY_DISABLED"),
                new Case(new com.securedoc.platform.application.exception.TenantInactiveException(
                        "Tenant is not active: " + tenantId), IntakeRejectionCode.TENANT_INACTIVE, "TENANT_INACTIVE"),
                new Case(new com.securedoc.platform.application.exception.TechnicalDependencyException(
                        com.securedoc.platform.application.exception.TechnicalDependency.STORAGE,
                        com.securedoc.platform.application.exception.TechnicalFailureCode.STORAGE_IO, true,
                        "Failed to store artifact"), IntakeRejectionCode.STORAGE_UNAVAILABLE, "STORAGE_IO"),
                new Case(new com.securedoc.platform.application.exception.ValidationException(
                        "Uploaded content is not a valid readable PDF"), IntakeRejectionCode.INVALID_FILE, "VALIDATION_ERROR"),
                new Case(new java.io.UncheckedIOException(new java.io.IOException("C:\\drops\\Jane Doe\\a.pdf")),
                        IntakeRejectionCode.SOURCE_IO_ERROR, "SOURCE_IO_ERROR"),
                new Case(new NullPointerException("Jane Doe"), IntakeRejectionCode.INTERNAL_ERROR, "INTERNAL_ERROR"));
        for (Case c : cases) {
            Fresh fresh = fresh(candidate("Jane Doe drop", doc("one.pdf", "one")), (command, p) -> { throw c.thrown(); });

            assertEquals(1, fresh.service.scan(principal, correlationId).rejected(), c.reason().name());

            assertEquals(IntakeObservationStatus.REJECTED, fresh.observations.latest.status());
            assertEquals(c.reason(), fresh.observations.latest.reasonCode(), c.thrown().getClass().getSimpleName());
            var rejected = fresh.audits.stream().filter(r -> r.action()
                    == com.securedoc.platform.domain.audit.AuditAction.INTAKE_REJECTED).findFirst().orElseThrow();
            // Task 0025: the LexaVault code and the detail come first; the rest of the record is as before.
            String lvCode = com.securedoc.platform.application.error.LvErrorCodes.optional(c.thrown())
                    .filter(code -> code != com.securedoc.platform.domain.error.LvErrorCode.SYS_001)
                    .orElse(com.securedoc.platform.application.error.LvErrorCodes.forIntakeRejection(c.reason())).code();
            assertEquals("code=" + lvCode + ", detail=" + c.reason() + ", "
                    + "Intake status=REJECTED, reasonCode=" + c.reason() + ", causeCode=" + c.causeCode()
                    + ", exceptionType=" + c.thrown().getClass().getSimpleName()
                    + ", drop=" + WatchedFolderDropReference.of("Jane Doe drop"), rejected.detail());
            fresh.audits.forEach(r -> assertFalse(r.detail() != null
                    && (r.detail().contains("Jane") || r.detail().contains("Secret-Key") || r.detail().contains("expired")),
                    r.detail()));
        }
    }

    @Test
    void aDropThatCannotBeReadWhileHashingIsAnIoErrorWithItsType() {
        UploadContent unreadable = new UploadContent() {
            @Override public InputStream openStream() { throw new java.io.UncheckedIOException(new java.io.IOException("C:\\drops\\x")); }
            @Override public long contentLength() { return 3; }
        };
        var candidate = candidate("drop-unreadable", new WatchedFolderDocument("one.pdf", "application/pdf", unreadable));
        Fresh fresh = fresh(candidate, (command, p) -> fail("not created"));

        assertEquals(1, fresh.service.scan(principal, correlationId).rejected());
        assertEquals(IntakeRejectionCode.SOURCE_IO_ERROR, fresh.observations.latest.reasonCode());
        assertTrue(fresh.audits.get(fresh.audits.size() - 1).detail()
                .contains("causeCode=SOURCE_IO_ERROR, exceptionType=UncheckedIOException"));
    }

    /* Task 0023 C2 / #41: a duplicate drop always writes a record. */

    @Test
    void theSameContentDroppedAgainIsRecordedAsADuplicateOnce() {
        Fresh fresh = fresh(candidate("drop-again", doc("one.pdf", "one")), (command, p) -> null);
        assertEquals(1, fresh.service.scan(principal, correlationId).accepted());
        PackageId accepted = fresh.observations.latest.packageId();

        ScanWatchedFolderResult again = fresh.service.scan(principal, correlationId);
        assertEquals(1, again.duplicates());
        assertEquals(IntakeObservationStatus.DUPLICATE, fresh.observations.latest.status());
        assertEquals(IntakeRejectionCode.DUPLICATE_INTAKE, fresh.observations.latest.reasonCode());
        assertEquals(accepted, fresh.observations.latest.packageId(), "the duplicate points at the case already made");
        long duplicateAudits = fresh.audits.stream().filter(r -> r.action()
                == com.securedoc.platform.domain.audit.AuditAction.INTAKE_DUPLICATE).count();
        assertEquals(1, duplicateAudits);

        // A drop left in place is seen on every scan: counted, but not recorded again.
        assertEquals(1, fresh.service.scan(principal, correlationId).duplicates());
        assertEquals(1, fresh.audits.stream().filter(r -> r.action()
                == com.securedoc.platform.domain.audit.AuditAction.INTAKE_DUPLICATE).count());
    }

    /* Task 0023 A4: a watched folder with no setting row saved gives Standard, end to end. */

    @Test
    void aFolderWithNoSettingSavedTakesFilesInAsStandard() {
        WatchedFolderSettingsService settings = new WatchedFolderSettingsService(new MemorySettings(),
                new AuditService(r -> { }), null);
        Fresh fresh = fresh(candidate("drop-standard", doc("one.pdf", "one"), doc("two.pdf", "two")), (command, p) -> null,
                tenant -> settings.sensitivityLevel(tenant, WatchedFolderSettingsService.INSTALLED_FOLDER));

        assertEquals(1, fresh.service.scan(principal, correlationId).accepted());
        assertEquals(SensitivityLevel.STANDARD, fresh.creates.get(0).caseSensitivity().level());
        assertFalse(fresh.creates.get(0).caseSensitivity().unknown());
        assertEquals(List.of(SensitivityLevel.STANDARD, SensitivityLevel.STANDARD), fresh.uploadLevels);
    }

    private final class Fresh {
        final MemoryPackageRepository repository = new MemoryPackageRepository(null);
        final ObservationStub observations = new ObservationStub();
        final List<com.securedoc.platform.domain.audit.AuditRecord> audits = new ArrayList<>();
        final List<CreatePackageCommand> creates = new ArrayList<>();
        final List<SensitivityLevel> uploadLevels = new ArrayList<>();
        WatchedFolderIntakeService service;
    }

    /** A harness with no package yet; {@code failure} may throw from create, or return null to create normally. */
    private Fresh fresh(WatchedFolderCandidate candidate, CreatePackageUseCase failure) {
        return fresh(candidate, failure, tenant -> SensitivityLevel.STANDARD);
    }

    private Fresh fresh(WatchedFolderCandidate candidate, CreatePackageUseCase failure,
                        java.util.function.Function<TenantId, SensitivityLevel> folderLevel) {
        Fresh fresh = new Fresh();
        CreatePackageUseCase create = (command, p) -> {
            fresh.creates.add(command);
            failure.execute(command, p);
            DocumentPackage pkg = DocumentPackage.createIntake(tenantId, PackageType.FOLDER_INTAKE,
                    BusinessRecordId.generate(), command.intakeFingerprint(), command.sourceReference(), actorId,
                    PackageMetadata.empty(), command.caseSensitivity().level());
            fresh.repository.current = pkg;
            return CreatePackageResult.of(pkg.id(), BusinessRecordId.generate(), pkg.status(), pkg.createdAt());
        };
        UploadDocumentUseCase upload = (command, p) -> {
            fresh.uploadLevels.add(command.sensitivityLevel());
            DocumentPackage pkg = fresh.repository.findByIdAndTenantId(command.packageId(), tenantId).orElseThrow();
            Document document = persisted(pkg.id(), new WatchedFolderDocument(command.fileName(), command.mimeType(), command.content()));
            pkg.addDocument(document);
            return UploadDocumentResult.of(document.id(), ArtifactId.generate(), document.status());
        };
        SubmitPackageUseCase submit = (command, p) -> fresh.repository.current.submit();
        fresh.service = new WatchedFolderIntakeService(new SourceStub(candidate), new LeaseStub(), fresh.observations,
                fresh.repository, create, upload, submit, command -> { }, new AuditService(fresh.audits::add),
                Duration.ofMinutes(15), Duration.ofSeconds(10), folderLevel);
        return fresh;
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Harness harness(MemoryPackageRepository repository,
                            WatchedFolderCandidate candidate,
                            boolean simulateCreateRace) {
        SourceStub source = new SourceStub(candidate);
        ObservationStub observations = new ObservationStub();
        LeaseStub lease = new LeaseStub();
        int[] uploads = {0};
        int[] submits = {0};
        int[] triggers = {0};

        CreatePackageUseCase create = (command, p) -> {
            if (simulateCreateRace) {
                DocumentPackage winner = DocumentPackage.createIntake(
                        tenantId, PackageType.FOLDER_INTAKE, BusinessRecordId.generate(),
                        command.intakeFingerprint(), command.sourceReference(), actorId, PackageMetadata.empty());
                repository.current = winner;
                throw new IllegalStateException("simulated unique constraint race");
            }
            fail("create should not be called when an existing fingerprint is already present");
            return null;
        };

        UploadDocumentUseCase upload = (command, p) -> {
            uploads[0]++;
            DocumentPackage pkg = repository.findByIdAndTenantId(command.packageId(), tenantId).orElseThrow();
            WatchedFolderDocument wd = new WatchedFolderDocument(command.fileName(), command.mimeType(), command.content());
            Document document = persisted(pkg.id(), wd);
            pkg.addDocument(document);
            repository.save(pkg);
            return UploadDocumentResult.of(document.id(), ArtifactId.generate(), document.status());
        };

        SubmitPackageUseCase submit = (command, p) -> {
            submits[0]++;
            DocumentPackage pkg = repository.findByIdAndTenantId(command.packageId(), tenantId).orElseThrow();
            pkg.submit();
            repository.save(pkg);
        };

        PackageProcessingDispatcher trigger = command -> triggers[0]++;
        AuditService audit = new AuditService(record -> {});

        WatchedFolderIntakeService service = new WatchedFolderIntakeService(
                source, lease, observations, repository, create, upload, submit, trigger, audit,
                Duration.ofMinutes(15), Duration.ofSeconds(10));
        return new Harness(service, source, observations, uploads, submits, triggers);
    }

    private DocumentPackage existingPackage(String sourceReference) {
        return DocumentPackage.createIntake(tenantId, PackageType.FOLDER_INTAKE, BusinessRecordId.generate(),
                "existing-fingerprint", sourceReference, actorId, PackageMetadata.empty());
    }

    private WatchedFolderCandidate candidate(String sourceReference, WatchedFolderDocument... documents) {
        return new WatchedFolderCandidate(sourceReference, true, true, null,
                Map.of("Contract Number", "000900000001"), Map.of(), List.of(documents));
    }

    private WatchedFolderDocument doc(String fileName, String content) {
        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        UploadContent upload = new UploadContent() {
            @Override public InputStream openStream() { return new ByteArrayInputStream(bytes); }
            @Override public long contentLength() { return bytes.length; }
        };
        return new WatchedFolderDocument(fileName, "application/pdf", upload);
    }

    private Document persisted(PackageId packageId, WatchedFolderDocument source) {
        return Document.create(tenantId, packageId, FileName.of(source.fileName()), MimeType.of(source.mimeType()),
                source.content().contentLength(), Checksum.sha256(sha256(source.content())), DocumentType.PDF);
    }

    private String sha256(UploadContent content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = content.openStream()) {
                byte[] buffer = new byte[1024];
                int n;
                while ((n = in.read(buffer)) >= 0) if (n > 0) digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private final class MemoryPackageRepository implements PackageRepository {
        private DocumentPackage current;
        private MemoryPackageRepository(DocumentPackage current) { this.current = current; }
        @Override public void save(DocumentPackage documentPackage) { current = documentPackage; }
        @Override public Optional<DocumentPackage> findByIdAndTenantId(PackageId id, TenantId tenantId) {
            return current != null && current.id().equals(id) && current.tenantId().equals(tenantId)
                    ? Optional.of(current) : Optional.empty();
        }
        @Override public List<DocumentPackage> findByTenantId(TenantId tenantId, int offset, int limit) {
            return current == null ? List.of() : List.of(current);
        }
        @Override public long countByTenantId(TenantId tenantId) { return current == null ? 0 : 1; }
        @Override public Optional<DocumentPackage> findByTenantIdAndIntakeFingerprint(TenantId tenantId, String fingerprint) {
            return current == null ? Optional.empty() : Optional.of(current);
        }
        @Override public List<DocumentPackage> findByStatus(PackageStatus status, int limit) {
            return current != null && current.status() == status ? List.of(current) : List.of();
        }
    }

    private static final class SourceStub implements WatchedFolderSourcePort {
        private final WatchedFolderCandidate candidate;
        private IntakeDisposition disposition;
        private SourceStub(WatchedFolderCandidate candidate) { this.candidate = candidate; }
        @Override public List<WatchedFolderCandidate> discover() { return List.of(candidate); }
        @Override public Optional<WatchedFolderCandidate> claim(String sourceReference, WatchedFolderScanLease lease, Instant claimedAt) {
            return Optional.of(candidate);
        }
        @Override public int recoverStaleClaims(WatchedFolderScanLease lease, Duration staleAfter, Instant currentTime) { return 0; }
        @Override public void applyDisposition(String sourceReference, IntakeDisposition disposition, WatchedFolderScanLease lease) {
            this.disposition = disposition;
        }
    }

    private static final class ObservationStub implements IntakeObservationRepository {
        private IntakeObservation latest;
        @Override public Optional<IntakeObservation> findByTenantAndSource(TenantId tenantId, String sourceReference) {
            return latest != null && latest.tenantId().equals(tenantId) && latest.sourceReference().equals(sourceReference)
                    ? Optional.of(latest) : Optional.empty();
        }
        @Override public void save(IntakeObservation observation) { latest = observation; }
        @Override public List<IntakeObservation> findRecentByTenant(TenantId tenantId, int limit) {
            return latest == null ? List.of() : List.of(latest);
        }
    }

    private static final class LeaseStub implements WatchedFolderLeasePort {
        @Override public Optional<LeaseSnapshot> acquireOrRenew(String leaseName, String ownerId, Duration leaseDuration) { return Optional.empty(); }
        @Override public Optional<LeaseSnapshot> current(String leaseName) { return Optional.empty(); }
        @Override public Instant currentTime() { return Instant.parse("2026-09-09T16:00:00Z"); }
        @Override public boolean isCurrent(String leaseName, String ownerId, long fencingToken) { return true; }
        @Override public boolean release(String leaseName, String ownerId, long fencingToken) { return true; }
    }

    private static final class Harness {
        private final WatchedFolderIntakeService service;
        private final SourceStub source;
        private final ObservationStub observations;
        private final int[] uploadsRef;
        private final int[] submitsRef;
        private final int[] triggersRef;
        private Harness(WatchedFolderIntakeService service, SourceStub source, ObservationStub observations,
                        int[] uploads, int[] submits, int[] triggers) {
            this.service = service; this.source = source; this.observations = observations;
            this.uploadsRef = uploads; this.submitsRef = submits; this.triggersRef = triggers;
        }
        private int uploads() { return uploadsRef[0]; }
        private int submits() { return submitsRef[0]; }
        private int triggers() { return triggersRef[0]; }
    }
}
