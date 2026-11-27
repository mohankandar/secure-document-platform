package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.document.version.DocumentVersionRecognition;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Task 0104: a case's batches and every file it ever received (tables case_batches, case_batch_files, and the version
 * columns of documents; migration 088). Fingerprints and hashes only, never a name or a value.
 */
public interface CaseBatchStore {

    /** Every document version the case holds or held (also one whose original is purged), as recognition compares them. */
    List<DocumentVersionRecognition.Known> knownVersions(TenantId tenantId, PackageId packageId);

    /** Opens the case's next batch and gives it; the first batch of a case is number 1. */
    Batch openBatch(TenantId tenantId, PackageId packageId, String source, Instant receivedAt);

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
