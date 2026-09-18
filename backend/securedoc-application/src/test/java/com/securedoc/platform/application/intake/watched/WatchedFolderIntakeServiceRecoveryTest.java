package com.securedoc.platform.application.intake.watched;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.intake.command.*;
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
        assertEquals("case-race", repository.current.sourceReference().orElseThrow());
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
