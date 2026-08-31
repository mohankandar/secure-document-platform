package com.securedoc.platform.application.export.result;

import com.securedoc.platform.domain.identity.DocumentId;

/**
 * Result of exporting a redacted document. Safe metadata only.
 */
public record ExportRedactedDocumentResult(
        DocumentId documentId,
        boolean exported,
        String destinationType
) {
    public static ExportRedactedDocumentResult of(DocumentId documentId, boolean exported,
                                                   String destinationType) {
        return new ExportRedactedDocumentResult(documentId, exported, destinationType);
    }
}
