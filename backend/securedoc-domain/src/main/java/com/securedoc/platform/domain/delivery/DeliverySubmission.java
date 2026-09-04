package com.securedoc.platform.domain.delivery;

import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.vo.Checksum;

import java.time.Instant;
import java.util.*;

/**
 * Stable identity/state for one package export cycle. The generated source submission/document IDs are
 * external integration identities and intentionally do not expose LexaVault's internal package/document UUIDs.
 */
public final class DeliverySubmission {
    private final UUID id;
    private final TenantId tenantId;
    private final PackageId packageId;
    private final int exportCycle;
    private final String sourceSubmissionId;
    private final String destinationType;
    private final List<DocumentState> documents;
    private final Instant createdAt;
    private DeliverySubmissionStatus status;
    private int attemptCount;
    private String destinationSubmissionReference;
    private String lastFailureCategory;
    private Instant updatedAt;
    private Instant completedAt;

    public DeliverySubmission(UUID id, TenantId tenantId, PackageId packageId, int exportCycle,
                              String sourceSubmissionId, String destinationType, DeliverySubmissionStatus status,
                              int attemptCount, String destinationSubmissionReference, String lastFailureCategory,
                              Instant createdAt, Instant updatedAt, Instant completedAt, List<DocumentState> documents) {
        this.id = Objects.requireNonNull(id);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.packageId = Objects.requireNonNull(packageId);
        if (exportCycle < 1) throw new IllegalArgumentException("exportCycle must be >= 1");
        this.exportCycle = exportCycle;
        this.sourceSubmissionId = require(sourceSubmissionId, "sourceSubmissionId");
        this.destinationType = require(destinationType, "destinationType");
        this.status = Objects.requireNonNull(status);
        if (attemptCount < 0) throw new IllegalArgumentException("attemptCount must be >= 0");
        this.attemptCount = attemptCount;
        this.destinationSubmissionReference = normalize(destinationSubmissionReference);
        this.lastFailureCategory = normalize(lastFailureCategory);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
        this.completedAt = completedAt;
        this.documents = new ArrayList<>(documents == null ? List.of() : documents);
        if (this.documents.isEmpty()) throw new IllegalArgumentException("Delivery submission requires documents");
    }

    public static DeliverySubmission create(TenantId tenantId, PackageId packageId, int exportCycle,
                                            String destinationType, List<NewDocument> docs, Instant now) {
        Objects.requireNonNull(now);
        if (docs == null || docs.isEmpty()) throw new IllegalArgumentException("Delivery submission requires documents");
        List<DocumentState> states = docs.stream().map(d -> new DocumentState(
                d.documentId(), "doc-" + UUID.randomUUID(), d.fileName(), d.sizeBytes(), d.checksum(),
                false, null, null, null)).toList();
        return new DeliverySubmission(UUID.randomUUID(), tenantId, packageId, exportCycle,
                "sub-" + UUID.randomUUID(), destinationType, DeliverySubmissionStatus.PENDING,
                0, null, null, now, now, null, states);
    }

    public int startAttempt(Instant now) {
        if (status == DeliverySubmissionStatus.DELIVERED) throw new IllegalStateException("Delivered submission cannot be retried");
        attemptCount++;
        status = DeliverySubmissionStatus.DELIVERING;
        lastFailureCategory = null;
        updatedAt = Objects.requireNonNull(now);
        return attemptCount;
    }

    public void recordDestinationSubmissionReference(String reference, Instant now) {
        String normalized = normalize(reference);
        if (normalized == null) throw new IllegalArgumentException("destination submission reference is required");
        if (destinationSubmissionReference != null && !destinationSubmissionReference.equals(normalized)) {
            throw new IllegalStateException("Destination submission reference cannot change during retry");
        }
        destinationSubmissionReference = normalized;
        updatedAt = Objects.requireNonNull(now);
    }

    public void acknowledgeDocument(DocumentId documentId, String destinationDocumentReference, String location, Instant now) {
        DocumentState current = document(documentId);
        if (!current.acknowledged()) {
            replace(current, current.acknowledged(destinationDocumentReference, location, now));
        }
        status = allAcknowledged() ? DeliverySubmissionStatus.DELIVERING : DeliverySubmissionStatus.PARTIALLY_ACKNOWLEDGED;
        updatedAt = Objects.requireNonNull(now);
    }

    public void markDelivered(String destinationSubmissionReference, Instant now) {
        if (!allAcknowledged()) throw new IllegalStateException("All documents must be acknowledged before delivery completion");
        String normalizedReference = normalize(destinationSubmissionReference);
        if (this.destinationSubmissionReference != null && normalizedReference != null
                && !this.destinationSubmissionReference.equals(normalizedReference)) {
            throw new IllegalStateException("Destination submission reference cannot change during completion");
        }
        if (normalizedReference != null) this.destinationSubmissionReference = normalizedReference;
        this.status = DeliverySubmissionStatus.DELIVERED;
        this.lastFailureCategory = null;
        this.completedAt = Objects.requireNonNull(now);
        this.updatedAt = now;
    }

    public void markFailure(String category, boolean retryable, Instant now) {
        this.lastFailureCategory = require(category, "failure category");
        this.status = retryable ? DeliverySubmissionStatus.RETRY_PENDING : DeliverySubmissionStatus.ATTENTION_REQUIRED;
        this.updatedAt = Objects.requireNonNull(now);
    }

    public boolean allAcknowledged() { return documents.stream().allMatch(DocumentState::acknowledged); }
    public List<DocumentState> pendingDocuments() { return documents.stream().filter(d -> !d.acknowledged()).toList(); }
    public Optional<DocumentState> findDocument(DocumentId id) { return documents.stream().filter(d -> d.documentId().equals(id)).findFirst(); }
    private DocumentState document(DocumentId id) { return findDocument(id).orElseThrow(() -> new IllegalArgumentException("Document is not part of delivery submission")); }
    private void replace(DocumentState old, DocumentState updated) { documents.set(documents.indexOf(old), updated); }
    private static String require(String v, String label) { if (v == null || v.isBlank()) throw new IllegalArgumentException(label + " is required"); return v.trim(); }
    private static String normalize(String v) { return v == null || v.isBlank() ? null : v.trim(); }

    public UUID id() { return id; }
    public TenantId tenantId() { return tenantId; }
    public PackageId packageId() { return packageId; }
    public int exportCycle() { return exportCycle; }
    public String sourceSubmissionId() { return sourceSubmissionId; }
    public String destinationType() { return destinationType; }
    public DeliverySubmissionStatus status() { return status; }
    public int attemptCount() { return attemptCount; }
    public String destinationSubmissionReference() { return destinationSubmissionReference; }
    public String lastFailureCategory() { return lastFailureCategory; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant completedAt() { return completedAt; }
    public List<DocumentState> documents() { return List.copyOf(documents); }

    public record NewDocument(DocumentId documentId, String fileName, long sizeBytes, Checksum checksum) {
        public NewDocument { Objects.requireNonNull(documentId); require(fileName, "fileName"); if (sizeBytes < 0) throw new IllegalArgumentException("sizeBytes must be >= 0"); Objects.requireNonNull(checksum); }
    }

    public record DocumentState(DocumentId documentId, String sourceDocumentId, String fileName, long sizeBytes,
                                Checksum checksum, boolean acknowledged, String destinationDocumentReference,
                                String destinationLocation, Instant acknowledgedAt) {
        public DocumentState { Objects.requireNonNull(documentId); require(sourceDocumentId,"sourceDocumentId"); require(fileName,"fileName"); if(sizeBytes<0) throw new IllegalArgumentException("sizeBytes must be >= 0"); Objects.requireNonNull(checksum); }
        DocumentState acknowledged(String ref, String location, Instant at) {
            return new DocumentState(documentId, sourceDocumentId, fileName, sizeBytes, checksum, true,
                    normalize(ref), normalize(location), Objects.requireNonNull(at));
        }
    }
}
