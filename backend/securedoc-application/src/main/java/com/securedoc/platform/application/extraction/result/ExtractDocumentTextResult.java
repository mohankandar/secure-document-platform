package com.securedoc.platform.application.extraction.result;

import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.DocumentId;

import java.util.Objects;

/**
 * Result of normalized document text extraction.
 * Contains safe metadata only — never the extracted text itself.
 */
public record ExtractDocumentTextResult(
        DocumentId documentId,
        ArtifactId extractedTextArtifactId,
        int pageCount,
        long characterCount,
        boolean hasText,
        boolean hasEmbeddedText,
        boolean usedOcr
) {

    public ExtractDocumentTextResult {
        Objects.requireNonNull(documentId, "DocumentId must not be null");
        // extractedTextArtifactId may be null only when extraction failed before an artifact was produced.
    }

    public static ExtractDocumentTextResult of(DocumentId documentId, ArtifactId extractedTextArtifactId,
                                                int pageCount, long characterCount,
                                                boolean hasText, boolean hasEmbeddedText, boolean usedOcr) {
        return new ExtractDocumentTextResult(documentId, extractedTextArtifactId,
                pageCount, characterCount, hasText, hasEmbeddedText, usedOcr);
    }
}
