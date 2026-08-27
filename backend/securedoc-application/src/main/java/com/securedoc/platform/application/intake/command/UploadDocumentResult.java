package com.securedoc.platform.application.intake.command;

import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.DocumentId;

import java.util.Objects;

/**
 * Result of a successful document upload.
 */
public record UploadDocumentResult(
        DocumentId documentId,
        ArtifactId artifactId,
        DocumentStatus status
) {

    public UploadDocumentResult {
        Objects.requireNonNull(documentId, "DocumentId must not be null");
        Objects.requireNonNull(artifactId, "ArtifactId must not be null");
        Objects.requireNonNull(status, "Status must not be null");
    }

    public static UploadDocumentResult of(DocumentId documentId, ArtifactId artifactId, DocumentStatus status) {
        return new UploadDocumentResult(documentId, artifactId, status);
    }
}
