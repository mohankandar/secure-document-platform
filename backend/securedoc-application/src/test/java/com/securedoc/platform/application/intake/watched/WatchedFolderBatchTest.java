package com.securedoc.platform.application.intake.watched;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.intake.batch.CaseBatchIntake;
import com.securedoc.platform.application.intake.batch.CaseBatchService;
import com.securedoc.platform.application.intake.command.*;
import com.securedoc.platform.application.port.in.*;
import com.securedoc.platform.application.port.out.*;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.document.*;
import com.securedoc.platform.domain.document.version.DocumentVersionRecognition;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.lifecycle.PackageLifecycle;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.FileName;
import com.securedoc.platform.domain.vo.MimeType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0104: a folder drop for a contract with an open case joins that case as its next batch instead of making a new
 * case; while the case is busy the drop is given back to wait, untouched. Synthetic content only.
 */
class WatchedFolderBatchTest {

    private final TenantId tenantId = TenantId.generate();
    private final ActorId actorId = ActorId.generate();
    private final AuthenticatedPrincipal principal = AuthenticatedPrincipal.of(
            actorId, tenantId, "watched-folder-system", Set.of("PLATFORM_OPERATIONS_ADMIN"));
    private final UUID record = UUID.randomUUID();

    @Test
    void aDropForADeliveredCaseJoinsItAndOnlyItsNewFileIsProcessed() {
        Harness h = new Harness(PackageStatus.COMPLETED, candidate("drop-2", doc("first.pdf", "first"), doc("second.pdf", "second")));

        ScanWatchedFolderResult result = h.service.scan(principal, CorrelationId.generate());

        assertEquals(1, result.accepted());
        assertEquals(0, h.creates, "no new case for the contract");
        assertEquals(List.of("second.pdf"), h.uploaded, "the delivered first.pdf is an exact copy: not stored again");
        assertEquals(PackageStatus.SUBMITTED, h.pkg.status());
        assertEquals(1, h.dispatched, "the batch goes to processing as it arrives");
        assertEquals(IntakeDisposition.ACCEPTED, h.source.disposition);
        assertEquals(List.of(DocumentVersionRecognition.Outcome.SKIPPED_IDENTICAL, DocumentVersionRecognition.Outcome.NEW_DOCUMENT),
                h.recordedOutcomes);
    }

    @Test
    void aDropForACaseInReviewWaitsUntouchedAndIsNotRefused() {
        Harness h = new Harness(PackageStatus.IN_REVIEW, candidate("drop-2", doc("second.pdf", "second")));

        ScanWatchedFolderResult result = h.service.scan(principal, CorrelationId.generate());

        assertEquals(0, result.accepted());
        assertEquals(0, result.rejected(), "never refused");
        assertEquals(1, result.waitingForTrigger());
        assertEquals(1, h.source.released, "given back to the incoming folder for a later scan");
        assertNull(h.source.disposition, "not archived, not moved to problems");
        assertNull(h.observations.latest, "nothing recorded while it waits");
        assertTrue(h.uploaded.isEmpty());
        assertEquals(0, h.creates);
        assertEquals(PackageStatus.IN_REVIEW, h.pkg.status());
    }

    @Test
    void theSamePackageResentIsAcceptedAndRecordedWithNothingProcessed() {
        Harness h = new Harness(PackageStatus.COMPLETED, candidate("drop-resent", doc("first.pdf", "first")));

        ScanWatchedFolderResult result = h.service.scan(principal, CorrelationId.generate());

        assertEquals(1, result.accepted(), "a business resend is never refused");
        assertEquals(0, result.duplicates());
        assertTrue(h.uploaded.isEmpty());
        assertEquals(0, h.dispatched);
        assertEquals(PackageStatus.COMPLETED, h.pkg.status());
        assertEquals(List.of(DocumentVersionRecognition.Outcome.SKIPPED_IDENTICAL), h.recordedOutcomes);
    }

    private final class Harness {
        final DocumentPackage pkg;
        final SourceStub source;
        final ObservationStub observations = new ObservationStub();
        final List<String> uploaded = new ArrayList<>();
        final List<DocumentVersionRecognition.Outcome> recordedOutcomes = new ArrayList<>();
        final WatchedFolderIntakeService service;
        int creates;
        int dispatched;

        Harness(PackageStatus status, WatchedFolderCandidate candidate) {
            source = new SourceStub(candidate);
            Document first = Document.create(tenantId, PackageId.generate(), FileName.of("first.pdf"),
                    MimeType.of("application/pdf"), 5, Checksum.sha256(sha("first")), DocumentType.PDF);
            first.startExtraction();
            first.completeExtraction(List.of(Page.withText(1, 612, 792, "synthetic")));
            first.startRedaction();
            first.completeProcessing();
            pkg = DocumentPackage.reconstitute(PackageId.generate(), tenantId, PackageType.FOLDER_INTAKE,
                    BusinessRecordId.of(record), "fingerprint-of-drop-1", "folder:drop-1", actorId, Instant.now(), status,
                    PackageMetadata.empty(), new ArrayList<>(List.of(first)), Instant.now());
            PackageRepository packages = new PackageRepository() {
                @Override public void save(DocumentPackage p) { }
                @Override public Optional<DocumentPackage> findByIdAndTenantId(PackageId id, TenantId t) {
                    return id.equals(pkg.id()) ? Optional.of(pkg) : Optional.empty();
                }
                @Override public List<DocumentPackage> findByTenantId(TenantId t, int offset, int limit) { return List.of(pkg); }
                @Override public long countByTenantId(TenantId t) { return 1; }
                @Override public Optional<DocumentPackage> findByTenantIdAndIntakeFingerprint(TenantId t, String f) {
                    return Optional.empty();
                }
                @Override public List<DocumentPackage> findByStatus(PackageStatus s, int limit) { return List.of(); }
            };
            UUID firstId = first.id().value();
            CaseBatchStore store = new CaseBatchStore() {
                final List<DocumentVersionRecognition.Known> known = new ArrayList<>(List.of(new DocumentVersionRecognition.Known(
                        firstId, firstId, 1, sha("first"), 5, CaseBatchService.nameHash("first.pdf"), null, null, null)));
                @Override public List<DocumentVersionRecognition.Known> knownVersions(TenantId t, PackageId p) { return known; }
                @Override public Optional<PackageId> openCaseFor(TenantId t, UUID r) {
                    return r.equals(record) ? Optional.of(pkg.id()) : Optional.empty();
                }
                @Override public Batch openBatch(TenantId t, PackageId p, String s, Instant at) { return new Batch(UUID.randomUUID(), 2); }
                @Override public Optional<BatchState> latestBatch(TenantId t, PackageId p) { return Optional.empty(); }
                @Override public Set<String> recordedFiles(TenantId t, UUID b) { return Set.of(); }
                @Override public void markNothingNew(TenantId t, UUID b) { }
                @Override public void recordFile(TenantId t, PackageId p, UUID b, ReceivedFile f) { recordedOutcomes.add(f.outcome()); }
                @Override public void setVersion(TenantId t, UUID d, UUID b, DocumentVersionRecognition.Result r,
                                                 String pdf, String xmp, String src) { }
            };
            PackageLifecycleRepository lifecycles = new PackageLifecycleRepository() {
                @Override public Optional<PackageLifecycle> findByPackageIdAndTenantId(PackageId p, TenantId t) {
                    return Optional.empty();
                }
                @Override public void save(PackageLifecycle l) { }
                @Override public List<PackageLifecycle> findDueRedactedPurge(Instant now, int limit) { return List.of(); }
                @Override public List<PackageLifecycle> findDueFinalPurge(Instant now, int limit) { return List.of(); }
            };
            UploadDocumentUseCase upload = (command, p) -> {
                assertTrue(command.isForNextBatch());
                uploaded.add(command.fileName());
                Document d = Document.createWithId(command.nextBatchDocumentId(), tenantId, pkg.id(), FileName.of(command.fileName()),
                        MimeType.of("application/pdf"), command.contentLength(), Checksum.sha256(sha(command.fileName())),
                        DocumentType.PDF, command.sensitivityLevel());
                pkg.addBatchDocument(d);
                return UploadDocumentResult.of(d.id(), ArtifactId.generate(), d.status());
            };
            SubmitPackageUseCase submit = (command, p) -> {
                assertTrue(command.nextBatch());
                pkg.submitNextBatch();
            };
            CreatePackageUseCase create = (command, p) -> {
                creates++;
                throw new AssertionError("a drop for an open case makes no new case");
            };
            CaseBatchIntake batches = new CaseBatchIntake(store, packages, lifecycles, upload, submit,
                    in -> PdfFileIdentityPort.PdfIdentity.NONE, Clock.systemUTC());
            service = new WatchedFolderIntakeService(source, new LeaseStub(), observations, packages, create, upload, submit,
                    command -> dispatched++, new AuditService(r -> { }), Duration.ofMinutes(15), Duration.ofSeconds(10))
                    .withFileCheck(in -> new PdfIntakeValidationPort.PdfInfo(1))
                    .withBatches(batches, (t, fields) -> record);
        }
    }

    private WatchedFolderCandidate candidate(String sourceReference, WatchedFolderDocument... documents) {
        return new WatchedFolderCandidate(sourceReference, true, true, null,
                Map.of("Contract Number", "779001126104"), Map.of(), List.of(documents));
    }

    private static WatchedFolderDocument doc(String fileName, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new WatchedFolderDocument(fileName, "application/pdf", new UploadContent() {
            @Override public InputStream openStream() { return new ByteArrayInputStream(bytes); }
            @Override public long contentLength() { return bytes.length; }
        });
    }

    private static String sha(String content) {
        return CaseBatchService.sha256(content.getBytes(StandardCharsets.UTF_8));
    }

    private static final class SourceStub implements WatchedFolderSourcePort {
        private final WatchedFolderCandidate candidate;
        private IntakeDisposition disposition;
        private int released;
        private SourceStub(WatchedFolderCandidate candidate) { this.candidate = candidate; }
        @Override public List<WatchedFolderCandidate> discover() { return List.of(candidate); }
        @Override public Optional<WatchedFolderCandidate> claim(String sourceReference, WatchedFolderScanLease lease, Instant claimedAt) {
            return Optional.of(candidate);
        }
        @Override public int recoverStaleClaims(WatchedFolderScanLease lease, Duration staleAfter, Instant currentTime) { return 0; }
        @Override public void applyDisposition(String sourceReference, IntakeDisposition disposition, WatchedFolderScanLease lease) {
            this.disposition = disposition;
        }
        @Override public void release(String sourceReference, WatchedFolderScanLease lease) { released++; }
    }

    private static final class ObservationStub implements IntakeObservationRepository {
        private IntakeObservation latest;
        @Override public Optional<IntakeObservation> findByTenantAndSource(TenantId tenantId, String sourceReference) {
            return Optional.ofNullable(latest).filter(o -> o.sourceReference().equals(sourceReference));
        }
        @Override public void save(IntakeObservation observation) { latest = observation; }
        @Override public List<IntakeObservation> findRecentByTenant(TenantId tenantId, int limit) {
            return latest == null ? List.of() : List.of(latest);
        }
    }

    private static final class LeaseStub implements WatchedFolderLeasePort {
        @Override public Optional<LeaseSnapshot> acquireOrRenew(String leaseName, String ownerId, Duration leaseDuration) { return Optional.empty(); }
        @Override public Optional<LeaseSnapshot> current(String leaseName) { return Optional.empty(); }
        @Override public Instant currentTime() { return Instant.parse("2026-11-28T12:00:00Z"); }
        @Override public boolean isCurrent(String leaseName, String ownerId, long fencingToken) { return true; }
        @Override public boolean release(String leaseName, String ownerId, long fencingToken) { return true; }
    }
}
