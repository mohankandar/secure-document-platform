package com.securedoc.platform.application.intake.batch;

import com.securedoc.platform.application.intake.command.SubmitPackageCommand;
import com.securedoc.platform.application.intake.command.UploadContent;
import com.securedoc.platform.application.intake.command.UploadDocumentCommand;
import com.securedoc.platform.application.port.in.SubmitPackageUseCase;
import com.securedoc.platform.application.port.in.UploadDocumentUseCase;
import com.securedoc.platform.application.port.out.CaseBatchStore;
import com.securedoc.platform.application.port.out.PackageLifecycleRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.PdfFileIdentityPort;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.document.version.DocumentVersionRecognition;
import com.securedoc.platform.domain.document.version.DocumentVersionRecognition.Outcome;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Task 0104 ({@code docs/design/CASE_VERSIONS.md}): an arrival for a contract that already has an open case joins that
 * case as its next batch, instead of making a new case. Each file is an exact copy (skipped, recorded, not processed or
 * counted), a new document or a new version; only the new ones are stored and processed. Refuses nothing for being a
 * resend.
 *
 * <p>The intakes call {@link #openCase} first: empty means "make a new case as before". A case that is processing, in
 * Review or still being filled is {@link Kind#BUSY}: the arrival waits (it is not refused and not merged into the running
 * batch). An interrupted join is resumed by the same arrival: the batch it opened is reused and the files already in it
 * are not stored again.
 */
public class CaseBatchIntake {

    public enum Kind { JOINED, NOTHING_NEW, BUSY }

    /**
     * @param processed new documents and new versions stored for processing
     * @param skipped   exact copies of a document the case has or had
     */
    public record Result(Kind kind, PackageId packageId, int batchNumber, int processed, int skipped) {}

    /**
     * One arriving file. {@code sourceDocumentId} is the id the source gives it (API document id, manifest
     * {@code documentId}); {@code replaces} the name or id it says it replaces; either may be null.
     */
    public record IncomingFile(String fileName, String mimeType, UploadContent content, String sourceDocumentId,
                               String replaces) {
        public IncomingFile {
            Objects.requireNonNull(fileName, "fileName");
            Objects.requireNonNull(content, "content");
        }
    }

    private final CaseBatchStore store;
    private final CaseBatchService batches;
    private final PackageRepository packages;
    private final PackageLifecycleRepository lifecycles;
    private final UploadDocumentUseCase upload;
    private final SubmitPackageUseCase submit;
    private final PdfFileIdentityPort identity;
    private final Clock clock;

    public CaseBatchIntake(CaseBatchStore store, PackageRepository packages, PackageLifecycleRepository lifecycles,
                           UploadDocumentUseCase upload, SubmitPackageUseCase submit, PdfFileIdentityPort identity,
                           Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.batches = new CaseBatchService(store);
        this.packages = Objects.requireNonNull(packages, "packages");
        this.lifecycles = Objects.requireNonNull(lifecycles, "lifecycles");
        this.upload = Objects.requireNonNull(upload, "upload");
        this.submit = Objects.requireNonNull(submit, "submit");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The open case of the contract an arrival resolves to: the newest case on its case-field record that is not
     * rejected, cancelled or erased. Empty: the arrival makes a new case, as before.
     */
    public Optional<DocumentPackage> openCase(TenantId tenantId, UUID businessRecordId) {
        return store.openCaseFor(tenantId, businessRecordId)
                .flatMap(id -> packages.findByIdAndTenantId(id, tenantId))
                .filter(DocumentPackage::isOpenForBatches);
    }

    /** True while the case can't take a batch now (processing, Review, being filled): the arrival waits. */
    public static boolean busy(DocumentPackage openCase) {
        return !openCase.takesNextBatchNow();
    }

    /**
     * Joins the arrival to the case as its next batch (or resumes the batch an interruption cut short), stores and
     * submits its new documents, and records every file. The case must be between batches; otherwise {@link Kind#BUSY}
     * and nothing is changed.
     */
    public Result join(AuthenticatedPrincipal principal, DocumentPackage openCase, String source, List<IncomingFile> files,
                       CorrelationId correlationId) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(openCase, "openCase");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(files, "files");
        TenantId tenant = principal.tenantId();
        PackageId caseId = openCase.id();
        if (busy(openCase)) return new Result(Kind.BUSY, caseId, 0, 0, 0);

        // An interrupted join left its batch open (RECEIVED, with stored documents not yet submitted): reuse it.
        boolean resuming = openCase.documents().stream().anyMatch(d -> d.status() == DocumentStatus.UPLOADED);
        Optional<CaseBatchStore.BatchState> latest = store.latestBatch(tenant, caseId);
        CaseBatchStore.Batch batch = resuming && latest.isPresent() && "RECEIVED".equals(latest.get().state())
                && latest.get().number() > 1
                ? new CaseBatchStore.Batch(latest.get().id(), latest.get().number())
                : store.openBatch(tenant, caseId, source, clock.instant());
        Set<String> alreadyRecorded = resuming ? store.recordedFiles(tenant, batch.id()) : Set.of();

        List<IncomingFile> toClassify = new ArrayList<>();
        List<DocumentVersionRecognition.Arrival> arrivals = new ArrayList<>();
        List<PdfFileIdentityPort.PdfIdentity> identities = new ArrayList<>();
        int resumedSkipped = 0;
        for (IncomingFile file : files) {
            PdfFileIdentityPort.PdfIdentity id = readIdentity(file.content());
            var arrival = CaseBatchService.arrival(file.content().contentLength(), file.content()::openStream,
                    file.fileName(), file.sourceDocumentId(), file.replaces(), id.pdfIdHash(), id.xmpDocumentIdHash());
            if (alreadyRecorded.contains(arrival.sha256())) {
                resumedSkipped++;
                continue; // stored (or recorded as a copy) before the interruption
            }
            toClassify.add(file);
            arrivals.add(arrival);
            identities.add(id);
        }

        List<CaseBatchService.Decision> decisions = batches.classify(tenant, caseId, arrivals);
        int processed = 0;
        int skipped = 0;
        for (int i = 0; i < decisions.size(); i++) {
            CaseBatchService.Decision decision = decisions.get(i);
            IncomingFile file = toClassify.get(i);
            DocumentVersionRecognition.Arrival arrival = arrivals.get(i);
            var r = decision.result();
            if (r.outcome() == Outcome.SKIPPED_IDENTICAL) {
                store.recordFile(tenant, caseId, batch.id(), new CaseBatchStore.ReceivedFile(arrival.sha256(),
                        arrival.sizeBytes(), null, arrival.nameHash(), r.outcome(), r.rule(), r.documentId()));
                skipped++;
                continue;
            }
            upload.execute(UploadDocumentCommand.forNextBatch(caseId, file.fileName(), file.mimeType(), file.content(),
                    correlationId, openCase.sensitivityLevel(), DocumentId.of(decision.newDocumentId())), principal);
            store.setVersion(tenant, decision.newDocumentId(), batch.id(), r, identities.get(i).pdfIdHash(),
                    identities.get(i).xmpDocumentIdHash(), arrival.sourceDocumentId());
            store.recordFile(tenant, caseId, batch.id(), new CaseBatchStore.ReceivedFile(arrival.sha256(),
                    arrival.sizeBytes(), null, arrival.nameHash(), r.outcome(), r.rule(), decision.newDocumentId()));
            processed++;
        }

        boolean somethingNew = processed > 0 || resuming;
        if (!somethingNew) {
            store.markNothingNew(tenant, batch.id());
            return new Result(Kind.NOTHING_NEW, caseId, batch.number(), 0, skipped + resumedSkipped);
        }
        // The keep period of the last delivery stops; this batch's own delivery sets it again.
        lifecycles.findByPackageIdAndTenantId(caseId, tenant).ifPresent(l -> {
            l.startNextBatch(clock.instant());
            lifecycles.save(l);
        });
        submit.execute(SubmitPackageCommand.nextBatch(caseId, correlationId), principal);
        return new Result(Kind.JOINED, caseId, batch.number(), processed + resumedSkipped, skipped);
    }

    private PdfFileIdentityPort.PdfIdentity readIdentity(UploadContent content) {
        try (InputStream in = content.openStream()) {
            PdfFileIdentityPort.PdfIdentity id = identity.read(in);
            return id == null ? PdfFileIdentityPort.PdfIdentity.NONE : id;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read an arriving file", e);
        }
    }
}
