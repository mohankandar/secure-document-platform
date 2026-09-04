package com.securedoc.platform.api.intake.external;

import java.util.List;
import java.util.Map;

public record ExternalIntakeCreateRequest(
        String externalSourcePackageId,
        String sourceRevision,
        Map<String, String> businessIdentifiers,
        Map<String, String> metadata,
        List<ManifestDocument> documents,
        Boolean retry) {
    public record ManifestDocument(String externalSourceDocumentId, String fileName, String sha256) { }
}
