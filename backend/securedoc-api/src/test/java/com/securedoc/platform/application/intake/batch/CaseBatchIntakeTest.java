package com.securedoc.platform.application.intake.batch;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.intake.command.SubmitPackageCommand;
import com.securedoc.platform.application.intake.command.UploadContent;
import com.securedoc.platform.application.intake.command.UploadDocumentCommand;
import com.securedoc.platform.application.port.in.SubmitPackageUseCase;
import com.securedoc.platform.application.port.in.UploadDocumentUseCase;
import com.securedoc.platform.application.port.out.CaseBatchStore;
import com.securedoc.platform.application.port.out.PackageLifecycleRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.PdfFileIdentityPort;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.document.DocumentType;
import com.securedoc.platform.domain.vo.FileName;
import com.securedoc.platform.domain.vo.MimeType;
import com.securedoc.platform.domain.document.PackageMetadata;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.document.Page;
import com.securedoc.platform.domain.document.version.DocumentVersionRecognition;
import com.securedoc.platform.domain.document.version.DocumentVersionRecognition.Known;
import com.securedoc.platform.domain.document.version.DocumentVersionRecognition.Outcome;
import com.securedoc.platform.domain.document.version.DocumentVersionRecognition.Rule;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 0104: an arrival for a contract's open case joins it as the next batch. The owner's examples: the rest of a
 * contract's documents later; a re-signed document; a whole document scanned again; a file re-saved by an e-signing
 * system; plus the same package resent, a busy case and an interrupted join. Synthetic bytes only.
 */
class CaseBatchIntakeTest {

    private final TenantId tenant = TenantId.generate();
    private final AuthenticatedPrincipal principal = AuthenticatedPrincipal.of(ActorId.generate(), tenant, "folder", Set.of());
    private final MemoryStore store = new MemoryStore();
    private final PackageRepository packages = mock(PackageRepository.class);
    private final UploadDocumentUseCase upload = mock(UploadDocumentUseCase.class);
    private final SubmitPackageUseCase submit = mock(SubmitPackageUseCase.class);
    private final Map<String, PdfFileIdentityPort.PdfIdentity> identities = new HashMap<>();
    private final PdfFileIdentityPort identity = in -> identities.getOrDefault(text(in), PdfFileIdentityPort.PdfIdentity.NONE);
    private final CaseBatchIntake intake = new CaseBatchIntake(store, packages, mock(PackageLifecycleRepository.class), upload,
            submit, identity, Clock.systemUTC());

    private DocumentPackage delivered;
    private UUID firstDocument;

    /** A delivered case of one document, "contract v1" named contract.pdf, recorded as batch 1. */
    private void deliveredCase(PackageStatus status) {
        Document first = Document.create(tenant, PackageId.generate(), FileName.of("contract.pdf"), MimeType.of("application/pdf"),
                11, Checksum.sha256(CaseBatchService.sha256(bytes("contract v1"))), DocumentType.PDF);
        first.startExtraction();
        first.completeExtraction(List.of(Page.withText(1, 612, 792, "synthetic")));
        first.startRedaction();
        first.completeProcessing();
        firstDocument = first.id().value();
        delivered = DocumentPackage.reconstitute(PackageId.generate(), tenant, PackageType.FOLDER_INTAKE, null, null, null,
                ActorId.generate(), Instant.now(), status, PackageMetadata.empty(), new ArrayList<>(List.of(first)), Instant.now());
        store.known.add(new Known(firstDocument, firstDocument, 1, CaseBatchService.sha256(bytes("contract v1")), 11,
                CaseBatchService.nameHash("contract.pdf"), "pdf-id-1", null, null));
        store.batches.add(new CaseBatchStore.BatchState(UUID.randomUUID(), 1, "DELIVERED"));
        when(packages.findByIdAndTenantId(delivered.id(), tenant)).thenReturn(Optional.of(delivered));
        doAnswer(inv -> {
            UploadDocumentCommand c = inv.getArgument(0);
            delivered.addBatchDocument(Document.createWithId(c.nextBatchDocumentId(), tenant, delivered.id(),
                    FileName.of(c.fileName()), MimeType.of("application/pdf"), c.contentLength(),
                    Checksum.sha256(CaseBatchService.sha256(c.content().openStream().readAllBytes())), DocumentType.PDF, c.sensitivityLevel()));
            return null;
        }).when(upload).execute(any(), any());
        doAnswer(inv -> {
            delivered.submitNextBatch();
            return null;
        }).when(submit).execute(any(), any());
    }

    @Test
    void theRestOfTheContractLaterJoinsTheCaseAsItsNextBatch() {
        deliveredCase(PackageStatus.COMPLETED);
        CaseBatchIntake.Result r = intake.join(principal, delivered, "WATCHED_FOLDER",
                List.of(file("letter.pdf", "a letter"), file("deed.pdf", "a deed"), file("note.pdf", "a note")), cid());
        assertEquals(CaseBatchIntake.Kind.JOINED, r.kind());
        assertEquals(2, r.batchNumber());
        assertEquals(3, r.processed());
        assertEquals(PackageStatus.SUBMITTED, delivered.status(), "the batch is processed as it arrives");
        assertEquals(4, delivered.documents().size(), "the delivered document stays in the case");
        assertEquals(3, delivered.documents().stream().filter(d -> d.status() == DocumentStatus.UPLOADED).count(),
                "only the batch's new documents are processed");
        assertTrue(store.recorded.stream().allMatch(f -> f.outcome() == Outcome.NEW_DOCUMENT));
    }

    /** Owner 2026-11-28 09:45: every file received before: refused as "already received" (the intake's LV-INT-017). */
    @Test
    void theSamePackageResentIsAllCopiesAndNothingIsWrittenProcessedOrCounted() {
        deliveredCase(PackageStatus.COMPLETED);
        int batchesBefore = store.batches.size();
        CaseBatchIntake.Result r = intake.join(principal, delivered, "WATCHED_FOLDER",
                List.of(file("contract-copy.pdf", "contract v1")), cid());
        assertEquals(CaseBatchIntake.Kind.ALL_COPIES, r.kind());
        assertEquals(delivered.id(), r.packageId());
        assertEquals(1, r.skipped());
        assertEquals(0, r.processed());
        verify(upload, never()).execute(any(), any());
        verify(submit, never()).execute(any(), any());
        assertEquals(PackageStatus.COMPLETED, delivered.status());
        assertTrue(store.recorded.isEmpty(), "nothing recorded: the intake refuses it");
        assertEquals(batchesBefore, store.batches.size(), "no batch opened");
    }

    /** A copy and a changed file: the copy is skipped and recorded, the changed one is processed (owner 2026-11-28). */
    @Test
    void aBatchWithACopyAndAChangedFileProcessesOnlyTheChangedOne() {
        deliveredCase(PackageStatus.COMPLETED);
        CaseBatchIntake.Result r = intake.join(principal, delivered, "WATCHED_FOLDER",
                List.of(file("contract-copy.pdf", "contract v1"), file("contract.pdf", "contract v1, corrected")), cid());
        assertEquals(CaseBatchIntake.Kind.JOINED, r.kind());
        assertEquals(1, r.skipped());
        assertEquals(1, r.processed());
        assertEquals(java.util.List.of(Outcome.SKIPPED_IDENTICAL, Outcome.NEW_VERSION),
                store.recorded.stream().map(CaseBatchStore.ReceivedFile::outcome).toList());
    }

    @Test
    void aReSignedDocumentThatBeginsWithTheOldBytesIsTheNextVersion() {
        deliveredCase(PackageStatus.COMPLETED);
        CaseBatchIntake.Result r = intake.join(principal, delivered, "WATCHED_FOLDER",
                List.of(file("contract-signed.pdf", "contract v1 + signature")), cid());
        assertEquals(CaseBatchIntake.Kind.JOINED, r.kind());
        CaseBatchStore.ReceivedFile f = store.recorded.getFirst();
        assertEquals(Outcome.NEW_VERSION, f.outcome());
        assertEquals(Rule.INCREMENTAL_UPDATE, f.rule());
        DocumentVersionRecognition.Result v = store.versions.get(f.documentId());
        assertEquals(2, v.versionNumber());
        assertEquals(firstDocument, v.replacesDocumentId(), "version 2 replaces version 1, which stays");
    }

    @Test
    void aFileReSavedByAnESigningSystemIsRecognisedByThePdfsOwnId() {
        deliveredCase(PackageStatus.COMPLETED);
        identities.put("completed envelope", new PdfFileIdentityPort.PdfIdentity("pdf-id-1", null));
        intake.join(principal, delivered, "WATCHED_FOLDER", List.of(file("Completed.pdf", "completed envelope")), cid());
        assertEquals(Outcome.NEW_VERSION, store.recorded.getFirst().outcome());
        assertEquals(Rule.PDF_ID, store.recorded.getFirst().rule());
    }

    @Test
    void aDocumentScannedAgainUnderTheSameNameIsAddedAndMarkedNeverRefused() {
        deliveredCase(PackageStatus.COMPLETED);
        CaseBatchIntake.Result r = intake.join(principal, delivered, "SCAN", List.of(file("CONTRACT.pdf", "rescanned pages")), cid());
        assertEquals(CaseBatchIntake.Kind.JOINED, r.kind());
        CaseBatchStore.ReceivedFile f = store.recorded.getFirst();
        assertEquals(Outcome.POSSIBLE_VERSION, f.outcome());
        assertEquals(firstDocument, store.versions.get(f.documentId()).possibleVersionOf(), "a person can link it");
        assertEquals(1, r.processed());
    }

    @Test
    void aCaseThatIsProcessingOrInReviewMakesTheArrivalWaitAndChangesNothing() {
        for (PackageStatus busy : List.of(PackageStatus.CREATED, PackageStatus.SUBMITTED, PackageStatus.PROCESSING,
                PackageStatus.PENDING_REVIEW, PackageStatus.IN_REVIEW, PackageStatus.APPROVED)) {
            store.batches.clear();
            store.recorded.clear();
            deliveredCase(busy);
            assertTrue(CaseBatchIntake.busy(delivered), busy.name());
            CaseBatchIntake.Result r = intake.join(principal, delivered, "WATCHED_FOLDER", List.of(file("x.pdf", "new")), cid());
            assertEquals(CaseBatchIntake.Kind.BUSY, r.kind(), busy.name());
            assertEquals(1, store.batches.size(), "no batch opened");
            assertTrue(store.recorded.isEmpty());
        }
        verify(upload, never()).execute(any(), any());
    }

    @Test
    void anInterruptedJoinIsResumedByTheSameArrivalWithoutStoringAFileTwice() {
        deliveredCase(PackageStatus.COMPLETED);
        // The first try stored "a letter" in batch 2 and stopped before the deed and the submit.
        CaseBatchIntake.Result r1 = null;
        doAnswer(inv -> {
            UploadDocumentCommand c = inv.getArgument(0);
            if (c.fileName().equals("deed.pdf")) throw new IllegalStateException("server stopped"); // nothing stored
            delivered.addBatchDocument(Document.createWithId(c.nextBatchDocumentId(), tenant, delivered.id(),
                    FileName.of(c.fileName()), MimeType.of("application/pdf"), c.contentLength(),
                    Checksum.sha256(CaseBatchService.sha256(c.content().openStream().readAllBytes())), DocumentType.PDF, c.sensitivityLevel()));
            return null;
        }).when(upload).execute(any(), any());
        List<CaseBatchIntake.IncomingFile> drop = List.of(file("letter.pdf", "a letter"), file("deed.pdf", "a deed"));
        try {
            r1 = intake.join(principal, delivered, "WATCHED_FOLDER", drop, cid());
        } catch (IllegalStateException expected) {
            // the interruption
        }
        assertEquals(null, r1);
        assertEquals(PackageStatus.COMPLETED, delivered.status());
        int batchesBefore = store.batches.size();

        doAnswer(inv -> {
            UploadDocumentCommand c = inv.getArgument(0);
            delivered.addBatchDocument(Document.createWithId(c.nextBatchDocumentId(), tenant, delivered.id(),
                    FileName.of(c.fileName()), MimeType.of("application/pdf"), c.contentLength(),
                    Checksum.sha256(CaseBatchService.sha256(c.content().openStream().readAllBytes())), DocumentType.PDF, c.sensitivityLevel()));
            return null;
        }).when(upload).execute(any(), any());
        CaseBatchIntake.Result r2 = intake.join(principal, delivered, "WATCHED_FOLDER", drop, cid());
        assertEquals(CaseBatchIntake.Kind.JOINED, r2.kind());
        assertEquals(batchesBefore, store.batches.size(), "the open batch is reused, not a third one");
        assertEquals(2, r2.batchNumber());
        assertEquals(2, delivered.documents().stream().filter(d -> d.status() == DocumentStatus.UPLOADED).count(),
                "the letter once, the deed once");
        assertEquals(PackageStatus.SUBMITTED, delivered.status());
        verify(submit).execute(any(SubmitPackageCommand.class), any());
    }

    private static CorrelationId cid() {
        return CorrelationId.generate();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(InputStream in) {
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static CaseBatchIntake.IncomingFile file(String name, String content) {
        byte[] b = bytes(content);
        return new CaseBatchIntake.IncomingFile(name, "application/pdf", new UploadContent() {
            @Override public InputStream openStream() { return new ByteArrayInputStream(b); }
            @Override public long contentLength() { return b.length; }
        }, null, null);
    }

    /** The store as the database keeps it: every file and version the case ever received. */
    private static final class MemoryStore implements CaseBatchStore {
        final List<Known> known = new ArrayList<>();
        final List<BatchState> batches = new ArrayList<>();
        final List<ReceivedFile> recorded = new ArrayList<>();
        final Map<UUID, Set<String>> byBatch = new HashMap<>();
        final Map<UUID, DocumentVersionRecognition.Result> versions = new HashMap<>();
        final Set<UUID> nothingNew = new HashSet<>();

        @Override public List<Known> knownVersions(TenantId t, PackageId p) {
            List<Known> all = new ArrayList<>(known);
            for (ReceivedFile f : recorded) {
                if (f.outcome() == Outcome.SKIPPED_IDENTICAL) continue;
                DocumentVersionRecognition.Result v = versions.get(f.documentId());
                all.add(new Known(f.documentId(), v.lineageId() == null ? f.documentId() : v.lineageId(),
                        Math.max(1, v.versionNumber()), f.sha256(), f.sizeBytes(), f.nameHash(), null, null, null));
            }
            return all;
        }
        @Override public Optional<PackageId> openCaseFor(TenantId t, UUID record) { return Optional.empty(); }
        @Override public Batch openBatch(TenantId t, PackageId p, String source, Instant at) {
            BatchState b = new BatchState(UUID.randomUUID(), batches.size() + 1, "RECEIVED");
            batches.add(b);
            return new Batch(b.id(), b.number());
        }
        @Override public Optional<BatchState> latestBatch(TenantId t, PackageId p) {
            return batches.isEmpty() ? Optional.empty() : Optional.of(batches.getLast());
        }
        @Override public Set<String> recordedFiles(TenantId t, UUID batchId) { return byBatch.getOrDefault(batchId, Set.of()); }
        @Override public void markNothingNew(TenantId t, UUID batchId) { nothingNew.add(batchId); }
        @Override public void recordFile(TenantId t, PackageId p, UUID batchId, ReceivedFile file) {
            recorded.add(file);
            byBatch.computeIfAbsent(batchId, k -> new HashSet<>()).add(file.sha256());
        }
        @Override public void setVersion(TenantId t, UUID documentId, UUID batchId, DocumentVersionRecognition.Result result,
                                         String pdf, String xmp, String src) {
            versions.put(documentId, result);
        }
    }
}
