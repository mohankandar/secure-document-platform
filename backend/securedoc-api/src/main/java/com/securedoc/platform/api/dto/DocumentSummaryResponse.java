package com.securedoc.platform.api.dto;

public record DocumentSummaryResponse(
        String documentId,
        String fileName,
        String mimeType,
        long sizeBytes,
        String documentType,
        String status
) {
}
