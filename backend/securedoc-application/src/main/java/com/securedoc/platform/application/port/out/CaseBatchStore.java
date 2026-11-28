package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.document.version.DocumentVersionRecognition;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Task 0104: a case's batches and every file it ever received (tables case_batches, case_batch_files, and the version
 * columns of documents; migration 093). Fingerprints and hashes only, never a name or a value.
 */
public interface CaseBatchStore {

    /** Every document version the case holds or held (also one whose original is purged), as recognition compares them. */
    List<DocumentVersionRecognition.Known> knownVersions(TenantId tenantId, PackageId packageId);

    /**
     * The open case of a contract: the newest case on this case-field record that is not rejected and not cancelled. A
     * case whose files were deleted by retention stays open for batches (owner 2026-11-28). Empty when there is none: the
     * arrival makes a new case.
     */
    java.util.Optional<PackageId> openCaseFor(TenantId tenantId, UUID businessRecordId);

    /**
     * Owner 2026-11-28: the open case whose contract has one of these keyed fingerprints (any key version), for an arrival
     * whose case-field record is new because the earlier values were erased. Same rules as {@link #openCaseFor}.
     */
    default java.util.Optional<PackageId> openCaseForContract(TenantId tenantId, List<String> contractKeys) {
        return java.util.Optional.empty();
    }

    /** Keeps the contract's keyed fingerprint with the case (once per key version); it goes when the case goes. */
    default void rememberContract(TenantId tenantId, PackageId packageId, String contractKey) {
    }

    /**
     * Records a case made without batches as its batch 1 (as {@link #openBatch} does first), so an arrival can be
     * compared against its files before any batch is opened. Nothing happens when the case has a batch.
     */
    default void ensureHistory(TenantId tenantId, PackageId packageId) {
    }

    /** The batch joined a case whose earlier batches' files had already been deleted; the case history says so. */
    default void markEarlierFilesDeleted(TenantId tenantId, UUID batchId) {
    }

    /**
     * Opens the case's next batch and gives it; the first batch of a case is number 1. A case made without batches (by an
     * intake before task 0104, or by a first arrival) first gets its documents recorded as batch 1, so the next batch is
     * compared against them and numbered 2.
     */
    Batch openBatch(TenantId tenantId, PackageId packageId, String source, Instant receivedAt);

    /** The case's newest batch, or empty when it has none. */
    java.util.Optional<BatchState> latestBatch(TenantId tenantId, PackageId packageId);

    /** The SHA-256 of every file already recorded in a batch (to resume a batch an interruption cut short). */
    java.util.Set<String> recordedFiles(TenantId tenantId, UUID batchId);

    /** A batch of exact copies only: recorded for the history, nothing to process or deliver. */
    void markNothingNew(TenantId tenantId, UUID batchId);

    /** State of a batch: RECEIVED, DELIVERED or NOTHING_NEW. */
    record BatchState(UUID id, int number, String state) {}

    /** Records one file of a batch and what it was; {@code documentId} is null for a skipped copy. */
    void recordFile(TenantId tenantId, PackageId packageId, UUID batchId, ReceivedFile file);

    /** Sets a stored document's place in its lineage. */
    void setVersion(TenantId tenantId, UUID documentId, UUID batchId, DocumentVersionRecognition.Result result,
                    String pdfIdHash, String xmpIdHash, String sourceDocumentIdHash);

    record Batch(UUID id, int number) {}

    /**
     * @param sha256     of the file's bytes
     * @param sizeBytes  its size
     * @param pageCount  its pages, or null when not read (a skipped copy)
     * @param nameHash   hash of its name, or null
     * @param outcome    what it was
     * @param rule       which rule decided
     * @param documentId the document it became, or the identical one it was skipped for
     */
    record ReceivedFile(String sha256, long sizeBytes, Integer pageCount, String nameHash,
                        DocumentVersionRecognition.Outcome outcome, DocumentVersionRecognition.Rule rule, UUID documentId) {}
}
