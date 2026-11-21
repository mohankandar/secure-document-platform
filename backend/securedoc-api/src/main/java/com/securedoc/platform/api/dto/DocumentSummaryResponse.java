package com.securedoc.platform.api.dto;

/**
 * One document in a case. {@code sensitivityLevel} is the document's own level as sent by the source system and
 * {@code effectiveSensitivityLevel} the higher of it and the case level (task 0021). Read-only.
 */
public record DocumentSummaryResponse(
        String documentId,
        String fileName,
        String mimeType,
        long sizeBytes,
        String documentType,
        String status,
        String sensitivityLevel,
        String effectiveSensitivityLevel
) {
}
