package com.securedoc.platform.domain.intake;

import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.*;

/**
 * External machine-intake manifest linked one-to-one to an existing LexaVault package.
 * It owns source reconciliation only; normal processing continues through DocumentPackage.
 */
public final class ExternalIntake {
    private final PackageId packageId;
    private final TenantId tenantId;
    private final IntegrationConnectionId integrationConnectionId;
    private final ExternalIntakeChannel channel;
    private final String externalSourcePackageId;
    private final String sourceRevision;
    private final int attemptNumber;
    private final String requestFingerprint;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final LinkedHashMap<String, ExternalManifestDocument> documents;
    private ExternalIntakeStatus status;
    private Instant acceptedAt;
    private Instant expiredAt;
    private Instant cleanupCompletedAt;
    private Instant updatedAt;

    private ExternalIntake(PackageId packageId, TenantId tenantId, IntegrationConnectionId integrationConnectionId,
                           ExternalIntakeChannel channel, String externalSourcePackageId, String sourceRevision, int attemptNumber,
                           String requestFingerprint, Instant createdAt, Instant expiresAt, List<ExternalManifestDocument> documents,
                           ExternalIntakeStatus status, Instant acceptedAt, Instant expiredAt,
                           Instant cleanupCompletedAt, Instant updatedAt) {
        this.packageId = Objects.requireNonNull(packageId);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.integrationConnectionId = Objects.requireNonNull(integrationConnectionId);
        this.channel = Objects.requireNonNull(channel);
        this.externalSourcePackageId = requireText(externalSourcePackageId, "External source package id", 255);
        this.sourceRevision = requireText(sourceRevision, "Source revision", 64);
        if (attemptNumber < 1) throw new IllegalArgumentException("External intake attempt number must be >= 1");
        this.attemptNumber = attemptNumber;
        this.requestFingerprint = requireText(requestFingerprint, "External intake request fingerprint", 64).toLowerCase();
        if (!this.requestFingerprint.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("External intake request fingerprint must be SHA-256 hex");
        this.createdAt = Objects.requireNonNull(createdAt);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        if (!expiresAt.isAfter(createdAt)) throw new IllegalArgumentException("External intake expiry must be after creation");
        if (documents == null || documents.isEmpty()) throw new IllegalArgumentException("External intake manifest must contain at least one document");
        this.documents = new LinkedHashMap<>();
        for (ExternalManifestDocument document : documents) {
            Objects.requireNonNull(document);
            String key = document.externalSourceDocumentId();
            if (this.documents.putIfAbsent(key, document) != null) {
                throw new IllegalArgumentException("Duplicate external source document id in manifest: " + key);
            }
        }
        this.status = Objects.requireNonNull(status);
        this.acceptedAt = acceptedAt;
        this.expiredAt = expiredAt;
        this.cleanupCompletedAt = cleanupCompletedAt;
        this.updatedAt = Objects.requireNonNull(updatedAt);
        validateState();
    }

    public static ExternalIntake create(PackageId packageId, TenantId tenantId,
                                        IntegrationConnectionId integrationConnectionId,
                                        ExternalIntakeChannel channel, String externalSourcePackageId,
                                        String sourceRevision, String requestFingerprint, Instant createdAt, Instant expiresAt,
                                        List<ExternalManifestDocument> documents) {
        return createAttempt(packageId, tenantId, integrationConnectionId, channel, externalSourcePackageId,
                sourceRevision, 1, requestFingerprint, createdAt, expiresAt, documents);
    }

    public static ExternalIntake createAttempt(PackageId packageId, TenantId tenantId,
                                               IntegrationConnectionId integrationConnectionId,
                                               ExternalIntakeChannel channel, String externalSourcePackageId,
                                               String sourceRevision, int attemptNumber, String requestFingerprint,
                                               Instant createdAt, Instant expiresAt,
                                               List<ExternalManifestDocument> documents) {
        return new ExternalIntake(packageId, tenantId, integrationConnectionId, channel,
                externalSourcePackageId, sourceRevision, attemptNumber, requestFingerprint, createdAt, expiresAt, documents,
                ExternalIntakeStatus.AWAITING_DOCUMENTS, null, null, null, createdAt);
    }

    public static ExternalIntake reconstitute(PackageId packageId, TenantId tenantId,
                                              IntegrationConnectionId integrationConnectionId,
                                              ExternalIntakeChannel channel, String externalSourcePackageId,
                                              String sourceRevision, int attemptNumber, String requestFingerprint, Instant createdAt, Instant expiresAt,
                                              List<ExternalManifestDocument> documents, ExternalIntakeStatus status,
                                              Instant acceptedAt, Instant expiredAt, Instant cleanupCompletedAt,
                                              Instant updatedAt) {
        return new ExternalIntake(packageId, tenantId, integrationConnectionId, channel,
                externalSourcePackageId, sourceRevision, attemptNumber, requestFingerprint, createdAt, expiresAt, documents,
                status, acceptedAt, expiredAt, cleanupCompletedAt, updatedAt);
    }

    public ExternalManifestDocument document(String externalSourceDocumentId) {
        ExternalManifestDocument document = documents.get(externalSourceDocumentId);
        if (document == null) throw new IllegalArgumentException("Document is not part of this intake manifest");
        return document;
    }

    public void ensureDocumentReceiptAllowed(String externalSourceDocumentId, String idempotencyKey, Instant now) {
        if (status != ExternalIntakeStatus.AWAITING_DOCUMENTS) {
            throw new IllegalStateException("Cannot receive document for intake in status: " + status);
        }
        Objects.requireNonNull(now, "Receipt time is required");
        if (!now.isBefore(expiresAt)) throw new IllegalStateException("External intake completion window has expired");
        String normalizedKey = requireText(idempotencyKey, "Idempotency key", 255);
        document(externalSourceDocumentId);
        for (ExternalManifestDocument other : documents.values()) {
            if (!other.externalSourceDocumentId().equals(externalSourceDocumentId)
                    && normalizedKey.equals(other.idempotencyKey())) {
                throw new IllegalArgumentException("Idempotency key is already used by another manifest document");
            }
        }
    }

    public void recordReceived(String externalSourceDocumentId, DocumentId documentId, String actualSha256,
                               String idempotencyKey, Instant now) {
        ensureDocumentReceiptAllowed(externalSourceDocumentId, idempotencyKey, now);
        String normalizedKey = requireText(idempotencyKey, "Idempotency key", 255);
        ExternalManifestDocument current = document(externalSourceDocumentId);
        documents.put(externalSourceDocumentId,
                current.markReceived(documentId, actualSha256, normalizedKey, now));
        updatedAt = now;
        if (receivedCount() == expectedCount()) {
            status = ExternalIntakeStatus.ACCEPTED_FOR_PROCESSING;
            acceptedAt = now;
        }
    }

    public boolean expireIfDue(Instant now) {
        if (status != ExternalIntakeStatus.AWAITING_DOCUMENTS || now.isBefore(expiresAt)) return false;
        status = ExternalIntakeStatus.INCOMPLETE_EXPIRED;
        expiredAt = now;
        updatedAt = now;
        return true;
    }

    public void markCleanupCompleted(Instant now) {
        if (status != ExternalIntakeStatus.INCOMPLETE_EXPIRED) {
            throw new IllegalStateException("Cleanup can only complete for an expired incomplete intake");
        }
        cleanupCompletedAt = Objects.requireNonNull(now);
        updatedAt = now;
    }

    public int expectedCount() { return documents.size(); }
    public int receivedCount() { return (int) documents.values().stream().filter(ExternalManifestDocument::received).count(); }
    public List<String> missingExternalSourceDocumentIds() {
        return documents.values().stream().filter(d -> !d.received()).map(ExternalManifestDocument::externalSourceDocumentId).toList();
    }
    public List<ExternalManifestDocument> documents() { return List.copyOf(documents.values()); }
    public PackageId packageId() { return packageId; }
    public TenantId tenantId() { return tenantId; }
    public IntegrationConnectionId integrationConnectionId() { return integrationConnectionId; }
    public ExternalIntakeChannel channel() { return channel; }
    public String externalSourcePackageId() { return externalSourcePackageId; }
    public String sourceRevision() { return sourceRevision; }
    public int attemptNumber() { return attemptNumber; }
    public String requestFingerprint() { return requestFingerprint; }
    public Instant createdAt() { return createdAt; }
    public Instant expiresAt() { return expiresAt; }
    public ExternalIntakeStatus status() { return status; }
    public Instant acceptedAt() { return acceptedAt; }
    public Instant expiredAt() { return expiredAt; }
    public Instant cleanupCompletedAt() { return cleanupCompletedAt; }
    public Instant updatedAt() { return updatedAt; }

    private void validateState() {
        if (status == ExternalIntakeStatus.ACCEPTED_FOR_PROCESSING && receivedCount() != expectedCount()) {
            throw new IllegalArgumentException("Accepted external intake must have every manifest document received");
        }
        if (status == ExternalIntakeStatus.ACCEPTED_FOR_PROCESSING && acceptedAt == null) {
            throw new IllegalArgumentException("Accepted external intake requires accepted timestamp");
        }
        if (status == ExternalIntakeStatus.INCOMPLETE_EXPIRED && expiredAt == null) {
            throw new IllegalArgumentException("Expired external intake requires expired timestamp");
        }
    }

    private static String requireText(String value, String label, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        String normalized = value.trim();
        if (normalized.length() > max) throw new IllegalArgumentException(label + " exceeds " + max + " characters");
        return normalized;
    }
}
