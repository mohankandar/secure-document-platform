package com.securedoc.platform.api.dto;

public record UpdateDocumentIntelligenceRequest(
        String mode,
        String activeBundleVersion
) {}
