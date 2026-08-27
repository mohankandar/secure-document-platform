package com.securedoc.platform.application.extraction.result;

import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.DocumentId;

import java.util.Objects;

/**
 * Result of a successful text extraction.
 * Contains safe metadata only — never the extracted text itself.
 */
public record ExtractDocumentTextResult(
        DocumentId documentId,
        ArtifactId extractedTextArtifactId,
        int pageCount,
        long characterCount,
        boolean hasEmbeddedText
) {

    public ExtractDocumentTextResult {
        Objects.requireNonNull(documentId, "DocumentId must not be null");
        // extractedTextArtifactId may be null when no embedded text was found
    }

    public static ExtractDocumentTextResult of(DocumentId documentId, ArtifactId extractedTextArtifactId,
                                                int pageCount, long characterCount, boolean hasEmbeddedText) {
        return new ExtractDocumentTextResult(documentId, extractedTextArtifactId,
                pageCount, characterCount, hasEmbeddedText);
    }
}
