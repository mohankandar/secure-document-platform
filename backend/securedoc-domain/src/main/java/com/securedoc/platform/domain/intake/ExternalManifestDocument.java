package com.securedoc.platform.domain.intake;

import com.securedoc.platform.domain.identity.DocumentId;

import java.time.Instant;
import java.util.Objects;

/** One expected PDF in an external source manifest. */
public record ExternalManifestDocument(
        String externalSourceDocumentId,
        String originalFileName,
        String declaredSha256,
        DocumentId receivedDocumentId,
        String receivedSha256,
        String idempotencyKey,
        Instant receivedAt) {

    public ExternalManifestDocument {
        externalSourceDocumentId = requireText(externalSourceDocumentId, "External source document id", 255);
        originalFileName = requireText(originalFileName, "Original file name", 255);
        declaredSha256 = normalizeSha256(declaredSha256, false);
        receivedSha256 = normalizeSha256(receivedSha256, false);
        idempotencyKey = normalizeOptional(idempotencyKey, 255);
        if ((receivedDocumentId == null) != (receivedAt == null)) {
            throw new IllegalArgumentException("Received document id and received timestamp must be set together");
        }
        if (receivedDocumentId != null && (receivedSha256 == null || idempotencyKey == null)) {
            throw new IllegalArgumentException("Received manifest document requires checksum and idempotency key");
        }
    }

    public static ExternalManifestDocument expected(String externalSourceDocumentId, String originalFileName,
                                                     String declaredSha256) {
        return new ExternalManifestDocument(externalSourceDocumentId, originalFileName, declaredSha256,
                null, null, null, null);
    }

    public boolean received() { return receivedDocumentId != null; }

    public ExternalManifestDocument markReceived(DocumentId documentId, String actualSha256,
                                                 String idempotencyKey, Instant receivedAt) {
        Objects.requireNonNull(documentId, "Document id is required");
        Objects.requireNonNull(receivedAt, "Received time is required");
        String normalizedActual = normalizeSha256(actualSha256, true);
        String normalizedIdempotency = requireText(idempotencyKey, "Idempotency key", 255);
        if (received()) throw new IllegalStateException("Manifest document is already received");
        if (declaredSha256 != null && !declaredSha256.equalsIgnoreCase(normalizedActual)) {
            throw new IllegalArgumentException("Document checksum does not match manifest checksum");
        }
        return new ExternalManifestDocument(externalSourceDocumentId, originalFileName, declaredSha256,
                documentId, normalizedActual, normalizedIdempotency, receivedAt);
    }

    public boolean matchesReceivedContent(String sha256) {
        String normalized = normalizeSha256(sha256, true);
        return receivedSha256 != null && receivedSha256.equalsIgnoreCase(normalized);
    }

    public static String normalizeSha256(String value, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) throw new IllegalArgumentException("SHA-256 checksum is required");
            return null;
        }
        String normalized = value.trim().toLowerCase();
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("SHA-256 checksum must be exactly 64 hexadecimal characters");
        }
        return normalized;
    }

    private static String requireText(String value, String label, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        String normalized = value.trim();
        if (normalized.length() > max) throw new IllegalArgumentException(label + " exceeds " + max + " characters");
        return normalized;
    }

    private static String normalizeOptional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > max) throw new IllegalArgumentException("Value exceeds " + max + " characters");
        return normalized;
    }
}
