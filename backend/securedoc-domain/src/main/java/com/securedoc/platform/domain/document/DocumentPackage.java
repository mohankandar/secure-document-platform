package com.securedoc.platform.domain.document;

import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.BusinessRecordId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Aggregate root representing a collection of documents submitted for processing.
 * A package moves through a defined lifecycle from creation through vault registration.
 */
public final class DocumentPackage {

    private final PackageId id;
    private final TenantId tenantId;
    private final PackageType packageType;
    private BusinessRecordId businessRecordId;
    private final String intakeFingerprint;
    private final String sourceReference;
    private final ActorId submittedBy;
    private final Instant createdAt;
    private PackageStatus status;
    private PackageMetadata metadata;
    private final List<Document> documents;
    private final SensitivityLevel sensitivityLevel;
    private Instant updatedAt;

    private DocumentPackage(PackageId id, TenantId tenantId, PackageType packageType,
                            BusinessRecordId businessRecordId, String intakeFingerprint, String sourceReference,
                            ActorId submittedBy, Instant createdAt, PackageMetadata metadata,
                            SensitivityLevel sensitivityLevel) {
        this.id = id;
        this.tenantId = tenantId;
        this.packageType = packageType;
        this.businessRecordId = businessRecordId;
        this.intakeFingerprint = normalizeOptional(intakeFingerprint);
        this.sourceReference = normalizeOptional(sourceReference);
        this.submittedBy = submittedBy;
        this.createdAt = createdAt;
        this.status = PackageStatus.CREATED;
        this.metadata = metadata;
        this.documents = new ArrayList<>();
        this.sensitivityLevel = sensitivityLevel == null ? SensitivityLevel.STANDARD : sensitivityLevel;
        this.updatedAt = createdAt;
    }

    /**
     * Creates a new document package.
     */
    public static DocumentPackage create(TenantId tenantId, PackageType packageType,
                                         ActorId submittedBy, PackageMetadata metadata) {
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(packageType, "PackageType must not be null");
        Objects.requireNonNull(submittedBy, "SubmittedBy must not be null");
        Objects.requireNonNull(metadata, "Metadata must not be null");

        return createIntake(tenantId, packageType, null, null, null, submittedBy, metadata);
    }


    /**
     * Creates a new intake batch linked to a long-lived business record.
     * DocumentPackage remains the internal aggregate name for compatibility, while its V1 business semantics are IntakeBatch.
     */
    public static DocumentPackage createIntake(TenantId tenantId, PackageType packageType,
                                               BusinessRecordId businessRecordId, String intakeFingerprint,
                                               String sourceReference, ActorId submittedBy,
                                               PackageMetadata metadata) {
        return createIntake(tenantId, packageType, businessRecordId, intakeFingerprint, sourceReference,
                submittedBy, metadata, SensitivityLevel.STANDARD);
    }

    /**
     * Creates a new intake batch with the case sensitivity level the source system sent (task 0021).
     * The level is kept as given; LexaVault never changes it.
     */
    public static DocumentPackage createIntake(TenantId tenantId, PackageType packageType,
                                               BusinessRecordId businessRecordId, String intakeFingerprint,
                                               String sourceReference, ActorId submittedBy,
                                               PackageMetadata metadata, SensitivityLevel sensitivityLevel) {
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(packageType, "PackageType must not be null");
        Objects.requireNonNull(submittedBy, "SubmittedBy must not be null");
        Objects.requireNonNull(metadata, "Metadata must not be null");
        Objects.requireNonNull(sensitivityLevel, "Sensitivity level must not be null");
        return new DocumentPackage(PackageId.generate(), tenantId, packageType, businessRecordId,
                intakeFingerprint, sourceReference, submittedBy, Instant.now(), metadata, sensitivityLevel);
    }

    /**
     * Reconstitutes a package from persisted state (for infrastructure layer use).
     */
    public static DocumentPackage reconstitute(PackageId id, TenantId tenantId, PackageType packageType,
                                               BusinessRecordId businessRecordId, String intakeFingerprint,
                                               String sourceReference, ActorId submittedBy, Instant createdAt,
                                               PackageStatus status, PackageMetadata metadata,
                                               List<Document> documents, Instant updatedAt) {
        return reconstitute(id, tenantId, packageType, businessRecordId, intakeFingerprint, sourceReference,
                submittedBy, createdAt, status, metadata, documents, updatedAt, SensitivityLevel.STANDARD);
    }

    /** Reconstitutes a package with its stored case sensitivity level. */
    public static DocumentPackage reconstitute(PackageId id, TenantId tenantId, PackageType packageType,
                                               BusinessRecordId businessRecordId, String intakeFingerprint,
                                               String sourceReference, ActorId submittedBy, Instant createdAt,
                                               PackageStatus status, PackageMetadata metadata,
                                               List<Document> documents, Instant updatedAt,
                                               SensitivityLevel sensitivityLevel) {
        DocumentPackage pkg = new DocumentPackage(id, tenantId, packageType, businessRecordId, intakeFingerprint,
                sourceReference, submittedBy, createdAt, metadata, sensitivityLevel);
        pkg.status = status;
        pkg.documents.addAll(documents);
        pkg.updatedAt = updatedAt;
        return pkg;
    }

    public void addDocument(Document document) {
        Objects.requireNonNull(document, "Document must not be null");
        if (status != PackageStatus.CREATED) {
            throw new com.securedoc.platform.domain.error.CodedStateException(LvErrorCode.INT_026, 
                    "Cannot add documents to package in status: " + status);
        }
        documents.add(document);
        updatedAt = Instant.now();
    }

    public void submit() {
        if (status != PackageStatus.CREATED) {
            throw new com.securedoc.platform.domain.error.CodedStateException(LvErrorCode.INT_026, "Cannot submit package in status: " + status);
        }
        if (documents.isEmpty()) {
            throw new com.securedoc.platform.domain.error.CodedStateException(LvErrorCode.INT_026, "Cannot submit package with no documents");
        }
        status = PackageStatus.SUBMITTED;
        updatedAt = Instant.now();
    }

    public void startProcessing() {
        if (status != PackageStatus.SUBMITTED) {
            throw new IllegalStateException("Cannot start processing package in status: " + status);
        }
        status = PackageStatus.PROCESSING;
        updatedAt = Instant.now();
    }

    public void completeProcessing() {
        if (status != PackageStatus.PROCESSING) {
            throw new IllegalStateException("Cannot complete processing for package in status: " + status);
        }
        status = PackageStatus.PENDING_REVIEW;
        updatedAt = Instant.now();
    }

    /**
     * Returns a package to active processing after the final actionable review finding is resolved.
     * Redaction/verification/delivery continue asynchronously in the V1 runtime.
     */
    public void resumeProcessingAfterReview() {
        if (status != PackageStatus.PENDING_REVIEW && status != PackageStatus.IN_REVIEW) {
            throw new IllegalStateException("Cannot resume post-review processing for package in status: " + status);
        }
        status = PackageStatus.PROCESSING;
        updatedAt = Instant.now();
    }


    /**
     * Completes a straight-through or post-review package after all documents are processed
     * and delivery has succeeded. No additional approval is implied or required.
     */
    public void completeSuccessfully() {
        if (status != PackageStatus.PROCESSING
                && status != PackageStatus.PENDING_REVIEW
                && status != PackageStatus.IN_REVIEW) {
            throw new IllegalStateException("Cannot complete successfully package in status: " + status);
        }
        status = PackageStatus.COMPLETED;
        updatedAt = Instant.now();
    }


    /** Holds a fully processed package before customer delivery when tenant policy requires final approval. */
    public void holdForFinalApproval() {
        if (status != PackageStatus.PROCESSING
                && status != PackageStatus.PENDING_REVIEW
                && status != PackageStatus.IN_REVIEW) {
            throw new IllegalStateException("Cannot hold for final approval package in status: " + status);
        }
        status = PackageStatus.PENDING_FINAL_APPROVAL;
        updatedAt = Instant.now();
    }

    /** Returns a final-approval-held package to processing so delivery can resume. */
    public void resumeAfterFinalApproval() {
        if (status != PackageStatus.PENDING_FINAL_APPROVAL) {
            throw new IllegalStateException("Cannot resume final approval package in status: " + status);
        }
        status = PackageStatus.PROCESSING;
        updatedAt = Instant.now();
    }

    /**
     * Task 0104: the case can take further batches of its contract: any case not rejected and not cancelled (a batch for
     * a rejected case makes a new case; an erased case is gone).
     */
    public boolean isOpenForBatches() {
        return status != PackageStatus.REJECTED && status != PackageStatus.CANCELLED;
    }

    /**
     * Task 0104: the case can start its next batch now: only between batches, once the batch before is delivered. A
     * batch arriving while one is processing or in Review waits; it is never refused or merged into the running one.
     */
    public boolean takesNextBatchNow() {
        return status == PackageStatus.COMPLETED;
    }

    /**
     * Task 0104 (owner 2026-11-28): the case of a contract whose earlier case-field values were erased takes the record its
     * joining batch's values were stored in, so it shows and delivers them. Only between batches.
     */
    public void relinkBusinessRecord(BusinessRecordId record) {
        Objects.requireNonNull(record, "record");
        if (!takesNextBatchNow()) {
            throw new com.securedoc.platform.domain.error.CodedStateException(LvErrorCode.INT_026,
                    "The case can't take a new batch in status: " + status);
        }
        businessRecordId = record;
        updatedAt = Instant.now();
    }

    /** Task 0104: adds a document (a new document or a new version) of the next batch to a delivered case. */
    public void addBatchDocument(Document document) {
        Objects.requireNonNull(document, "Document must not be null");
        if (!takesNextBatchNow()) {
            throw new com.securedoc.platform.domain.error.CodedStateException(LvErrorCode.INT_026,
                    "The case can't take a new batch in status: " + status);
        }
        documents.add(document);
        updatedAt = Instant.now();
    }

    /** Task 0104: the next batch goes to processing; only with something new in it (a skipped copy is not processed). */
    public void submitNextBatch() {
        if (!takesNextBatchNow()) {
            throw new com.securedoc.platform.domain.error.CodedStateException(LvErrorCode.INT_026,
                    "The case can't take a new batch in status: " + status);
        }
        if (documents.stream().noneMatch(d -> d.status() == DocumentStatus.UPLOADED)) {
            throw new com.securedoc.platform.domain.error.CodedStateException(LvErrorCode.INT_026,
                    "The batch has no new document to process");
        }
        status = PackageStatus.SUBMITTED;
        updatedAt = Instant.now();
    }

    /** Resets a previously completed intake batch for another business-requested processing/export cycle. */
    public void prepareForReprocessing() {
        if (status != PackageStatus.COMPLETED) {
            throw new com.securedoc.platform.domain.error.CodedStateException(LvErrorCode.REV_002, "Only a completed package can be reprocessed");
        }
        status = PackageStatus.SUBMITTED;
        updatedAt = Instant.now();
    }

    /** Returns a technically failed package to SUBMITTED so only reset/failed work is retried. */
    public void prepareForTechnicalRetry() {
        if (status != PackageStatus.FAILED) {
            throw new com.securedoc.platform.domain.error.CodedStateException(LvErrorCode.REV_002, "Only a failed package can be retried");
        }
        status = PackageStatus.SUBMITTED;
        updatedAt = Instant.now();
    }

    /**
     * Moves a technically failed package into business-controlled manual processing.
     * The technical incident remains recorded separately in audit/technical visibility.
     */
    public void prepareForManualFallback() {
        if (status != PackageStatus.FAILED) {
            throw new com.securedoc.platform.domain.error.CodedStateException(LvErrorCode.REV_008, "Only a failed package can enter manual fallback");
        }
        status = PackageStatus.IN_REVIEW;
        updatedAt = Instant.now();
    }

    public void startReview() {
        if (status != PackageStatus.PENDING_REVIEW) {
            throw new com.securedoc.platform.domain.error.CodedStateException(LvErrorCode.REV_002, "Cannot start review for package in status: " + status);
        }
        status = PackageStatus.IN_REVIEW;
        updatedAt = Instant.now();
    }

    public void approve() {
        if (status != PackageStatus.IN_REVIEW) {
            throw new com.securedoc.platform.domain.error.CodedStateException(LvErrorCode.REV_002, "Cannot approve package in status: " + status);
        }
        status = PackageStatus.APPROVED;
        updatedAt = Instant.now();
    }

    public void reject() {
        if (status != PackageStatus.PENDING_REVIEW && status != PackageStatus.IN_REVIEW
                && status != PackageStatus.PENDING_FINAL_APPROVAL) {
            throw new com.securedoc.platform.domain.error.CodedStateException(LvErrorCode.REV_002, "Cannot reject package in status: " + status);
        }
        status = PackageStatus.REJECTED;
        updatedAt = Instant.now();
    }

    public void vault() {
        if (status != PackageStatus.APPROVED) {
            throw new IllegalStateException("Cannot vault package in status: " + status);
        }
        status = PackageStatus.VAULTED;
        updatedAt = Instant.now();
    }

    public void fail() {
        if (status == PackageStatus.VAULTED || status == PackageStatus.CANCELLED) {
            throw new IllegalStateException("Cannot mark as failed package in status: " + status);
        }
        status = PackageStatus.FAILED;
        updatedAt = Instant.now();
    }

    public void cancel() {
        if (status == PackageStatus.VAULTED) {
            throw new IllegalStateException("Cannot cancel a vaulted package");
        }
        status = PackageStatus.CANCELLED;
        updatedAt = Instant.now();
    }

    public void updateMetadata(PackageMetadata metadata) {
        Objects.requireNonNull(metadata, "Metadata must not be null");
        this.metadata = metadata;
        this.updatedAt = Instant.now();
    }

    // --- Accessors ---

    public PackageId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public PackageType packageType() {
        return packageType;
    }

    public java.util.Optional<BusinessRecordId> businessRecordId() {
        return java.util.Optional.ofNullable(businessRecordId);
    }

    public java.util.Optional<String> intakeFingerprint() {
        return java.util.Optional.ofNullable(intakeFingerprint);
    }

    public java.util.Optional<String> sourceReference() {
        return java.util.Optional.ofNullable(sourceReference);
    }

    public ActorId submittedBy() {
        return submittedBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public PackageStatus status() {
        return status;
    }

    public PackageMetadata metadata() {
        return metadata;
    }

    public List<Document> documents() {
        return Collections.unmodifiableList(documents);
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /** The case level the source system sent; {@link SensitivityLevel#STANDARD} when it sent none. */
    public SensitivityLevel sensitivityLevel() {
        return sensitivityLevel;
    }

    /** The case's effective level ({@link SensitivityLevel#effectiveForCase}). */
    public SensitivityLevel effectiveSensitivityLevel() {
        return SensitivityLevel.effectiveForCase(sensitivityLevel,
                documents.stream().map(Document::sensitivityLevel).toList());
    }

    public int documentCount() {
        return documents.size();
    }

    private static String normalizeOptional(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

