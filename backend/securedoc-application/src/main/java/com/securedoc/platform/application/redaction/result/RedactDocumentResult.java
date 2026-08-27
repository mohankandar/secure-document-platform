package com.securedoc.platform.application.redaction.result;

import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.DocumentId;

import java.util.Objects;

/**
 * Result of a successful redaction operation.
 * Contains safe metadata only — never raw sensitive values or file content.
 */
public record RedactDocumentResult(
        DocumentId documentId,
        ArtifactId redactedArtifactId,
        DocumentStatus resultingStatus,
        int redactionCount
) {

    public RedactDocumentResult {
        Objects.requireNonNull(documentId, "DocumentId must not be null");
        Objects.requireNonNull(resultingStatus, "ResultingStatus must not be null");
        // redactedArtifactId may be null when redaction failed closed
    }

    public static RedactDocumentResult of(DocumentId documentId, ArtifactId redactedArtifactId,
                                           DocumentStatus resultingStatus, int redactionCount) {
        return new RedactDocumentResult(documentId, redactedArtifactId, resultingStatus, redactionCount);
    }
}
