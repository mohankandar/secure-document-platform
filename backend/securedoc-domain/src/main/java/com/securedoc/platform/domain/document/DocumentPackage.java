package com.securedoc.platform.domain.document;

import com.securedoc.platform.domain.identity.ActorId;
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
    private final ActorId submittedBy;
    private final Instant createdAt;
    private PackageStatus status;
    private PackageMetadata metadata;
    private final List<Document> documents;
    private Instant updatedAt;

    private DocumentPackage(PackageId id, TenantId tenantId, PackageType packageType,
                            ActorId submittedBy, Instant createdAt, PackageMetadata metadata) {
        this.id = id;
        this.tenantId = tenantId;
        this.packageType = packageType;
        this.submittedBy = submittedBy;
        this.createdAt = createdAt;
        this.status = PackageStatus.CREATED;
        this.metadata = metadata;
        this.documents = new ArrayList<>();
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

        return new DocumentPackage(
                PackageId.generate(),
                tenantId,
                packageType,
                submittedBy,
                Instant.now(),
                metadata
        );
    }

    /**
     * Reconstitutes a package from persisted state (for infrastructure layer use).
     */
    public static DocumentPackage reconstitute(PackageId id, TenantId tenantId, PackageType packageType,
                                               ActorId submittedBy, Instant createdAt, PackageStatus status,
                                               PackageMetadata metadata, List<Document> documents,
                                               Instant updatedAt) {
        DocumentPackage pkg = new DocumentPackage(id, tenantId, packageType, submittedBy, createdAt, metadata);
        pkg.status = status;
        pkg.documents.addAll(documents);
        pkg.updatedAt = updatedAt;
        return pkg;
    }

    public void addDocument(Document document) {
        Objects.requireNonNull(document, "Document must not be null");
        if (status != PackageStatus.CREATED) {
            throw new IllegalStateException(
                    "Cannot add documents to package in status: " + status);
        }
        documents.add(document);
        updatedAt = Instant.now();
    }

    public void submit() {
        if (status != PackageStatus.CREATED) {
            throw new IllegalStateException("Cannot submit package in status: " + status);
        }
        if (documents.isEmpty()) {
            throw new IllegalStateException("Cannot submit package with no documents");
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

    public void startReview() {
        if (status != PackageStatus.PENDING_REVIEW) {
            throw new IllegalStateException("Cannot start review for package in status: " + status);
        }
        status = PackageStatus.IN_REVIEW;
        updatedAt = Instant.now();
    }

    public void approve() {
        if (status != PackageStatus.IN_REVIEW) {
            throw new IllegalStateException("Cannot approve package in status: " + status);
        }
        status = PackageStatus.APPROVED;
        updatedAt = Instant.now();
    }

    public void reject() {
        if (status != PackageStatus.IN_REVIEW) {
            throw new IllegalStateException("Cannot reject package in status: " + status);
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

    public int documentCount() {
        return documents.size();
    }
}
