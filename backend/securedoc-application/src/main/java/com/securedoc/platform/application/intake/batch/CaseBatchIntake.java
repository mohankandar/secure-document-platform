package com.securedoc.platform.application.intake.batch;

import com.securedoc.platform.application.intake.command.SubmitPackageCommand;
import com.securedoc.platform.application.intake.command.UploadContent;
import com.securedoc.platform.application.intake.command.UploadDocumentCommand;
import com.securedoc.platform.application.port.in.SubmitPackageUseCase;
import com.securedoc.platform.application.port.in.UploadDocumentUseCase;
import com.securedoc.platform.application.port.out.CaseBatchStore;
import com.securedoc.platform.application.port.out.PackageDashboardVisibilityPort;
import com.securedoc.platform.application.port.out.PackageLifecycleRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.PdfFileIdentityPort;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.document.version.DocumentVersionRecognition;
import com.securedoc.platform.domain.document.version.DocumentVersionRecognition.Outcome;
import com.securedoc.platform.domain.identity.BusinessRecordId;
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
 * counted), a new document or a new version; only the new ones are stored and processed.
 *
 * <p>An arrival whose files are <b>all</b> exact copies of files the case received before is {@link Kind#ALL_COPIES}
 * (owner 2026-11-28 09:45): nothing is written, and the intake refuses it on its existing "already received" path
 * (LV-INT-017), so it is counted where duplicates are counted.
 *
 * <p>The intakes call {@link #openCase} first: empty means "make a new case as before". A case that is processing, in
 * Review or still being filled is {@link Kind#BUSY}: the arrival waits (it is not refused and not merged into the running
 * batch). An interrupted join is resumed by the same arrival: the batch it opened is reused and the files already in it
 * are not stored again.
 *
 * <p>Owner 2026-11-28 (decision (a)): a case whose files (and case-field values) were already deleted by retention still
 * takes the contract's next batch. It is found by the contract's keyed fingerprint when the arrival's values made a new
 * case-field record; the case is pointed at that record, and the batch history says the earlier files were deleted.
 */
public class CaseBatchIntake {

    public enum Kind { JOINED, ALL_COPIES, BUSY }

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

    /**
     * Who the arrival's case fields say the contract is: the case-field record its values resolved to, and the
     * contract's keyed fingerprints (current key version first; empty without a key service).
     */
    public record Contract(UUID businessRecordId, List<String> keys) {
        public Contract {
            keys = keys == null ? List.of() : List.copyOf(keys);
        }

        public static Contract record(UUID businessRecordId) {
            return new Contract(businessRecordId, List.of());
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
    private PackageDashboardVisibilityPort visibility = new PackageDashboardVisibilityPort() {
        @Override
        public void hide(PackageId packageId, TenantId tenantId, com.securedoc.platform.domain.identity.ActorId actorId,
                         java.time.Instant hiddenAt) {
        }
    };

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

    /** A case hidden from the dashboard after its files were deleted shows again when its next batch joins. */
    public CaseBatchIntake withVisibility(PackageDashboardVisibilityPort visibility) {
        this.visibility = Objects.requireNonNull(visibility);
        return this;
    }

    /**
     * The open case of the contract an arrival resolves to: the newest case on its case-field record that is not
     * rejected or cancelled. Empty: the arrival makes a new case, as before.
     */
    public Optional<DocumentPackage> openCase(TenantId tenantId, UUID businessRecordId) {
        return openCase(tenantId, Contract.record(businessRecordId));
    }

    /**
     * As {@link #openCase(TenantId, UUID)}; when the record has no case (its earlier values were erased, so the arrival's
     * values made a new record), the case whose contract has the same keyed fingerprint.
     */
    public Optional<DocumentPackage> openCase(TenantId tenantId, Contract contract) {
        Optional<PackageId> found = store.openCaseFor(tenantId, contract.businessRecordId());
        if (found.isEmpty()) found = store.openCaseForContract(tenantId, contract.keys());
        return found.flatMap(id -> packages.findByIdAndTenantId(id, tenantId))
                .filter(DocumentPackage::isOpenForBatches);
    }

    /** True while the case can't take a batch now (processing, Review, being filled): the arrival waits. */
    public static boolean busy(DocumentPackage openCase) {
        return !openCase.takesNextBatchNow();
    }

    public Result join(AuthenticatedPrincipal principal, DocumentPackage openCase, String source, List<IncomingFile> files,
                       CorrelationId correlationId) {
        return join(principal, openCase, source, files, correlationId, null);
    }

    /**
     * Joins the arrival to the case as its next batch (or resumes the batch an interruption cut short), stores and
     * submits its new documents, and records every file. The files are compared first: when every one is an exact copy
     * the result is {@link Kind#ALL_COPIES} and nothing is changed, also for a busy case. Otherwise a case that is not
     * between batches is {@link Kind#BUSY} and nothing is changed.
     *
     * @param contract the arrival's contract (record and keyed fingerprints), or null when the intake has none
     */
    public Result join(AuthenticatedPrincipal principal, DocumentPackage openCase, String source, List<IncomingFile> files,
                       CorrelationId correlationId, Contract contract) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(openCase, "openCase");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(files, "files");
        TenantId tenant = principal.tenantId();
        PackageId caseId = openCase.id();
        boolean between = !busy(openCase);
        // A case made without batches gets its documents recorded as batch 1 first, so the arrival is compared with them.
        store.ensureHistory(tenant, caseId);

        // An interrupted join left its batch open (RECEIVED, with stored documents not yet submitted): reuse it.
        boolean resuming = between && openCase.documents().stream().anyMatch(d -> d.status() == DocumentStatus.UPLOADED);
        Optional<CaseBatchStore.BatchState> latest = store.latestBatch(tenant, caseId);
        Optional<CaseBatchStore.Batch> resumable = resuming && latest.isPresent()
                && "RECEIVED".equals(latest.get().state()) && latest.get().number() > 1
                ? Optional.of(new CaseBatchStore.Batch(latest.get().id(), latest.get().number())) : Optional.empty();
        Set<String> alreadyRecorded = resumable.map(b -> store.recordedFiles(tenant, b.id())).orElse(Set.of());

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
        boolean anythingNew = decisions.stream().anyMatch(d -> d.result().outcome() != Outcome.SKIPPED_IDENTICAL);
        if (!anythingNew && !resuming && !files.isEmpty()) {
            // Owner 2026-11-28 09:45: every file was received before: refused as "already received", nothing recorded.
            return new Result(Kind.ALL_COPIES, caseId, 0, 0, decisions.size());
        }
        if (!between) return new Result(Kind.BUSY, caseId, 0, 0, 0);

        CaseBatchStore.Batch batch = resumable.orElseGet(() -> store.openBatch(tenant, caseId, source, clock.instant()));
        DocumentPackage joining = openCase;
        if (contract != null && contract.businessRecordId() != null && !openCase.businessRecordId()
                .map(r -> r.value().equals(contract.businessRecordId())).orElse(false)) {
            // The earlier values were erased: the case takes the record this batch's values were stored in.
            joining.relinkBusinessRecord(BusinessRecordId.of(contract.businessRecordId()));
            packages.save(joining);
        }
        if (contract != null && !contract.keys().isEmpty()) store.rememberContract(tenant, caseId, contract.keys().getFirst());
        // The keep period of the last delivery stops; this batch's own delivery sets it again. Files deleted already: the
        // case takes the batch all the same, and its history says so.
        lifecycles.findByPackageIdAndTenantId(caseId, tenant).ifPresent(l -> {
            if (l.startNextBatch(clock.instant())) {
                store.markEarlierFilesDeleted(tenant, batch.id());
                visibility.show(caseId, tenant);
            }
            lifecycles.save(l);
        });

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
