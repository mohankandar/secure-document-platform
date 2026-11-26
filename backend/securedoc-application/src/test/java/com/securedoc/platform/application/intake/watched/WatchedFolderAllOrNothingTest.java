package com.securedoc.platform.application.intake.watched;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.intake.command.*;
import com.securedoc.platform.application.intake.model.IntakeRejectionCode;
import com.securedoc.platform.application.port.in.*;
import com.securedoc.platform.application.port.out.*;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.document.*;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.FileName;
import com.securedoc.platform.domain.vo.MimeType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0102 (owner test U3): a folder drop is all or nothing. A case is made only when every file the manifest lists can
 * be taken in; otherwise no case is left on the dashboard and one intake problem is recorded with its reason.
 */
class WatchedFolderAllOrNothingTest {

    private final TenantId tenantId = TenantId.of(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private final ActorId actorId = ActorId.of(UUID.fromString("22222222-2222-2222-2222-222222222222"));
    private final AuthenticatedPrincipal principal = AuthenticatedPrincipal.of(
            actorId, tenantId, "watched-folder-system", Set.of("PLATFORM_OPERATIONS_ADMIN"));
    private final CorrelationId correlationId = CorrelationId.generate();

    /** Content that the PDF check refuses as the real adapter does for a password-protected file. */
    private static final String LOCKED = "locked";

    @Test
    void aGoodFileAndAPasswordProtectedFileLeaveNoCaseAndOneIntakeProblem() {
        Harness h = new Harness(candidate("drop-locked", doc("good.pdf", "good"), doc("locked.pdf", LOCKED)));

        ScanWatchedFolderResult result = h.service.scan(principal, correlationId);

        assertEquals(1, result.rejected());
        assertEquals(0, result.accepted());
        assertEquals(0, h.creates, "no case is made");
        assertEquals(0, h.uploads, "no file is stored");
        assertNull(h.repository.current);
        assertOneIntakeProblem(h, IntakeRejectionCode.INVALID_FILE, "LV-INT-008");
    }

    @Test
    void aListedFileThatIsMissingLeavesNoCaseAndOneIntakeProblem() {
        // The folder adapter refuses a manifest naming a file that is not there (MISSING_FILE) before the service sees it.
        var candidate = new WatchedFolderCandidate("drop-missing", true, false, IntakeRejectionCode.MISSING_FILE,
                Map.of(), Map.of(), List.of());
        Harness h = new Harness(candidate);

        assertEquals(1, h.service.scan(principal, correlationId).rejected());
        assertEquals(0, h.creates, "no case is made");
        assertNull(h.repository.current);
        assertOneIntakeProblem(h, IntakeRejectionCode.MISSING_FILE, "LV-INT-");
    }

    @Test
    void anEmptyFileOrOneOverThePageLimitIsRefusedBeforeACaseIsMade() {
        Harness empty = new Harness(candidate("drop-empty", doc("good.pdf", "good"), doc("empty.pdf", "")));
        assertEquals(1, empty.service.scan(principal, correlationId).rejected());
        assertEquals(0, empty.creates);
        assertOneIntakeProblem(empty, IntakeRejectionCode.INVALID_FILE, "LV-INT-006");

        Harness pages = new Harness(candidate("drop-pages", doc("good.pdf", "good"), doc("pages.pdf", "pages:501")));
        assertEquals(1, pages.service.scan(principal, correlationId).rejected());
        assertEquals(0, pages.creates);
        assertOneIntakeProblem(pages, IntakeRejectionCode.INVALID_FILE, "LV-INT-028");
    }

    @Test
    void everyGoodFileStillMakesOneCase() {
        Harness h = new Harness(candidate("drop-good", doc("one.pdf", "one"), doc("two.pdf", "two")));

        assertEquals(1, h.service.scan(principal, correlationId).accepted());
        assertEquals(1, h.creates);
        assertEquals(2, h.uploads);
        assertEquals(PackageStatus.SUBMITTED, h.repository.current.status());
        assertTrue(h.hidden.isEmpty());
    }

    /** Fallback: a file that fails while being stored (after the check) leaves no case on the dashboard either. */
    @Test
    void aCaseHalfMadeBeforeARefusalIsClosedOutOfTheDashboardAndKept() {
        Harness h = new Harness(candidate("drop-store-fails", doc("one.pdf", "one"), doc("two.pdf", "two")));
        h.failUploadOf = "two.pdf";

        assertEquals(1, h.service.scan(principal, correlationId).rejected());

        assertEquals(1, h.creates);
        DocumentPackage kept = h.repository.current;
        assertNotNull(kept, "kept for reports");
        assertEquals(PackageStatus.CANCELLED, kept.status());
        assertEquals(List.of(kept.id()), h.hidden, "hidden from the dashboard");
        assertEquals(IntakeObservationStatus.REJECTED, h.observations.latest.status());
        assertNull(h.observations.latest.packageId(), "the problem names no case");
        AuditRecord closed = h.audits.stream().filter(r -> r.action() == AuditAction.PACKAGE_CANCELLED)
                .findFirst().orElseThrow();
        assertTrue(closed.detail().startsWith("Refused at intake: reasonCode=STORAGE_UNAVAILABLE"), closed.detail());
        assertEquals(IntakeDisposition.REJECTED, h.source.disposition);
    }

    /** Crash between making the case and taking its files: the retry of the claimed drop closes it. */
    @Test
    void aCaseLeftByACrashIsClosedWhenTheRetriedDropIsRefused() {
        var candidate = candidate("drop-crashed", doc("good.pdf", "good"), doc("locked.pdf", LOCKED));
        Harness h = new Harness(candidate);
        DocumentPackage leftOver = DocumentPackage.createIntake(tenantId, PackageType.FOLDER_INTAKE, BusinessRecordId.generate(),
                "fp", WatchedFolderDropReference.of("drop-crashed"), actorId, PackageMetadata.empty());
        h.repository.current = leftOver;

        assertEquals(1, h.service.scan(principal, correlationId).rejected());

        assertEquals(0, h.creates);
        assertEquals(PackageStatus.CANCELLED, leftOver.status());
        assertEquals(List.of(leftOver.id()), h.hidden);
        assertEquals(IntakeObservationStatus.REJECTED, h.observations.latest.status());
    }

    @Test
    void aCaseThatIsAlreadyUnderWayIsNeverClosedByARefusal() {
        var candidate = candidate("drop-submitted", doc("one.pdf", "different"));
        Harness h = new Harness(candidate);
        DocumentPackage submitted = DocumentPackage.createIntake(tenantId, PackageType.FOLDER_INTAKE, BusinessRecordId.generate(),
                "fp", WatchedFolderDropReference.of("drop-submitted"), actorId, PackageMetadata.empty());
        submitted.addDocument(persisted(submitted.id(), doc("one.pdf", "one")));
        submitted.submit();
        h.repository.current = submitted;

        assertEquals(1, h.service.scan(principal, correlationId).rejected(), "recovery conflict");
        assertEquals(PackageStatus.SUBMITTED, submitted.status());
        assertTrue(h.hidden.isEmpty());
    }

    private void assertOneIntakeProblem(Harness h, IntakeRejectionCode reason, String lvCodePrefix) {
        assertEquals(IntakeObservationStatus.REJECTED, h.observations.latest.status());
        assertEquals(reason, h.observations.latest.reasonCode());
        assertEquals(1, h.observations.saves, "one intake-problem record");
        List<AuditRecord> rejected = h.audits.stream().filter(r -> r.action() == AuditAction.INTAKE_REJECTED).toList();
        assertEquals(1, rejected.size());
        assertTrue(rejected.get(0).detail().startsWith("code=" + lvCodePrefix), rejected.get(0).detail());
        assertEquals(IntakeDisposition.REJECTED, h.source.disposition, "the folder goes to _problem");
        assertTrue(h.hidden.isEmpty());
        assertTrue(h.audits.stream().noneMatch(r -> r.action() == AuditAction.PACKAGE_CANCELLED));
    }

    /* ---------- harness ---------- */

    private final class Harness {
        final MemoryPackageRepository repository = new MemoryPackageRepository();
        final ObservationStub observations = new ObservationStub();
        final SourceStub source;
        final List<AuditRecord> audits = new ArrayList<>();
        final List<PackageId> hidden = new ArrayList<>();
        final WatchedFolderIntakeService service;
        int creates;
        int uploads;
        String failUploadOf;

        Harness(WatchedFolderCandidate candidate) {
            source = new SourceStub(candidate);
            CreatePackageUseCase create = (command, p) -> {
                creates++;
                DocumentPackage pkg = DocumentPackage.createIntake(tenantId, PackageType.FOLDER_INTAKE,
                        BusinessRecordId.generate(), command.intakeFingerprint(), command.sourceReference(), actorId,
                        PackageMetadata.empty(), command.caseSensitivity().level());
                repository.current = pkg;
                return CreatePackageResult.of(pkg.id(), BusinessRecordId.generate(), pkg.status(), pkg.createdAt());
            };
            UploadDocumentUseCase upload = (command, p) -> {
                if (command.fileName().equals(failUploadOf)) {
                    throw new com.securedoc.platform.application.exception.TechnicalDependencyException(
                            com.securedoc.platform.application.exception.TechnicalDependency.STORAGE,
                            com.securedoc.platform.application.exception.TechnicalFailureCode.STORAGE_IO, true,
                            "Failed to store artifact");
                }
                // As the real upload: the same PDF check, so a file the service did not check first fails here.
                try (InputStream in = command.content().openStream()) {
                    check(in);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                uploads++;
                DocumentPackage pkg = repository.findByIdAndTenantId(command.packageId(), tenantId).orElseThrow();
                Document document = persisted(pkg.id(), new WatchedFolderDocument(command.fileName(), command.mimeType(), command.content()));
                pkg.addDocument(document);
                return UploadDocumentResult.of(document.id(), ArtifactId.generate(), document.status());
            };
            SubmitPackageUseCase submit = (command, p) -> repository.current.submit();
            PackageDashboardVisibilityPort visibility = (packageId, tenant, actor, at) -> hidden.add(packageId);
            service = new WatchedFolderIntakeService(source, new LeaseStub(), observations, repository, create, upload, submit,
                    command -> { }, new AuditService(audits::add), Duration.ofMinutes(15), Duration.ofSeconds(10))
                    .withFileCheck(WatchedFolderAllOrNothingTest::check)
                    .withDashboardVisibility(visibility);
        }
    }

    /** The PDF check: "locked" is refused as password-protected, "pages:N" has N pages, anything else one page. */
    private static PdfIntakeValidationPort.PdfInfo check(InputStream in) {
        String content;
        try {
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        if (content.equals(LOCKED)) {
            throw new ValidationException(LvErrorCode.INT_008, "Password-protected file. Send it through the API.");
        }
        if (content.startsWith("pages:")) return new PdfIntakeValidationPort.PdfInfo(Integer.parseInt(content.substring(6)));
        return new PdfIntakeValidationPort.PdfInfo(1);
    }

    private WatchedFolderCandidate candidate(String sourceReference, WatchedFolderDocument... documents) {
        return new WatchedFolderCandidate(sourceReference, true, true, null,
                Map.of("Contract Number", "779001126006"), Map.of(), List.of(documents));
    }

    private WatchedFolderDocument doc(String fileName, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
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

    private static String sha256(UploadContent content) {
        try (InputStream in = content.openStream()) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final class MemoryPackageRepository implements PackageRepository {
        private DocumentPackage current;
        @Override public void save(DocumentPackage documentPackage) { current = documentPackage; }
        @Override public Optional<DocumentPackage> findByIdAndTenantId(PackageId id, TenantId tenantId) {
            return current != null && current.id().equals(id) ? Optional.of(current) : Optional.empty();
        }
        @Override public List<DocumentPackage> findByTenantId(TenantId tenantId, int offset, int limit) {
            return current == null ? List.of() : List.of(current);
        }
        @Override public long countByTenantId(TenantId tenantId) { return current == null ? 0 : 1; }
        @Override public Optional<DocumentPackage> findByTenantIdAndIntakeFingerprint(TenantId tenantId, String fingerprint) {
            return Optional.ofNullable(current);
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
        private int saves;
        @Override public Optional<IntakeObservation> findByTenantAndSource(TenantId tenantId, String sourceReference) {
            return latest != null && latest.sourceReference().equals(sourceReference) ? Optional.of(latest) : Optional.empty();
        }
        @Override public void save(IntakeObservation observation) { latest = observation; saves++; }
        @Override public List<IntakeObservation> findRecentByTenant(TenantId tenantId, int limit) {
            return latest == null ? List.of() : List.of(latest);
        }
    }

    private static final class LeaseStub implements WatchedFolderLeasePort {
        @Override public Optional<LeaseSnapshot> acquireOrRenew(String leaseName, String ownerId, Duration leaseDuration) { return Optional.empty(); }
        @Override public Optional<LeaseSnapshot> current(String leaseName) { return Optional.empty(); }
        @Override public Instant currentTime() { return Instant.parse("2026-11-26T12:00:00Z"); }
        @Override public boolean isCurrent(String leaseName, String ownerId, long fencingToken) { return true; }
        @Override public boolean release(String leaseName, String ownerId, long fencingToken) { return true; }
    }
}
