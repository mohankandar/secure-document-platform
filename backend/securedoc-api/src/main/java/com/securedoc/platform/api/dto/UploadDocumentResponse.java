package com.securedoc.platform.api.dto;

public record UploadDocumentResponse(
        String documentId,
        String artifactId,
        String status
) {
}
