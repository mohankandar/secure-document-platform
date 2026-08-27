package com.securedoc.platform.application.vault.result;

import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.DocumentId;

import java.io.InputStream;
import java.util.Objects;

/**
 * Result of downloading an artifact from the vault.
 * Contains the content stream, safe filename, and metadata.
 * Never exposes the underlying file path.
 */
public record DownloadArtifactResult(
        ArtifactId artifactId,
        DocumentId documentId,
        ArtifactType artifactType,
        String mimeType,
        long sizeBytes,
        String downloadFilename,
        InputStream content
) {
    public DownloadArtifactResult {
        Objects.requireNonNull(artifactId, "ArtifactId must not be null");
        Objects.requireNonNull(documentId, "DocumentId must not be null");
        Objects.requireNonNull(artifactType, "ArtifactType must not be null");
        Objects.requireNonNull(mimeType, "MimeType must not be null");
        Objects.requireNonNull(downloadFilename, "DownloadFilename must not be null");
        Objects.requireNonNull(content, "Content must not be null");
    }

    /**
     * Generates a safe download filename that does not expose the original filename.
     */
    public static String safeFilename(DocumentId documentId, ArtifactType artifactType) {
        String suffix = switch (artifactType) {
            case REDACTED -> "redacted.pdf";
            case ORIGINAL -> "original.pdf";
            case EXTRACTED_TEXT -> "extracted.txt";
            default -> "artifact.bin";
        };
        return "document-" + documentId.value() + "-" + suffix;
    }
}
