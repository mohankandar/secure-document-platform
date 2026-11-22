package com.securedoc.platform.application.support.model;

import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.identity.DocumentId;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Task 0038: where a problem about one document sits in its case, for Support: "document 2 of 3, page 14". Never the
 * file name. The position is the document's place in upload order (upload time, then id, so two files uploaded in the
 * same instant keep one stable order). The page is shown only when the problem recorded one ({@code page} or
 * {@code pageNumber}, a whole number from 1); otherwise it is null.
 */
public record DocumentPosition(int position, int count, Integer page) {

    public DocumentPosition {
        if (count < 1 || position < 1 || position > count) throw new IllegalArgumentException("position out of range");
        if (page != null && page < 1) throw new IllegalArgumentException("page must be 1 or more");
    }

    /** Empty when the document is not one of the case's documents (fail closed: no position is guessed). */
    public static Optional<DocumentPosition> of(List<Document> caseDocuments, DocumentId documentId,
                                                Map<String, String> problemMetadata) {
        if (documentId == null || caseDocuments == null || caseDocuments.isEmpty()) return Optional.empty();
        List<Document> ordered = caseDocuments.stream()
                .sorted(Comparator.comparing(Document::uploadedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(d -> d.id().value()))
                .toList();
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).id().equals(documentId)) {
                return Optional.of(new DocumentPosition(i + 1, ordered.size(), page(problemMetadata)));
            }
        }
        return Optional.empty();
    }

    private static Integer page(Map<String, String> metadata) {
        if (metadata == null) return null;
        for (String key : List.of("page", "pageNumber")) {
            String value = metadata.get(key);
            if (value != null && value.trim().matches("[1-9][0-9]{0,5}")) return Integer.valueOf(value.trim());
        }
        return null;
    }
}
