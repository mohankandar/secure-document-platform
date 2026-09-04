package com.securedoc.platform.application.intake.external;

import java.util.List;
import java.util.Map;

public record PushIntakeCreateInput(
        String externalSourcePackageId,
        String sourceRevision,
        Map<String, String> businessIdentifiers,
        Map<String, String> metadata,
        List<ManifestDocumentInput> documents,
        boolean retry) {
    public PushIntakeCreateInput {
        businessIdentifiers = businessIdentifiers == null ? Map.of() : Map.copyOf(businessIdentifiers);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        documents = documents == null ? List.of() : List.copyOf(documents);
    }

    public PushIntakeCreateInput(String externalSourcePackageId, String sourceRevision,
                                 Map<String, String> businessIdentifiers, Map<String, String> metadata,
                                 List<ManifestDocumentInput> documents) {
        this(externalSourcePackageId, sourceRevision, businessIdentifiers, metadata, documents, false);
    }

    public record ManifestDocumentInput(String externalSourceDocumentId, String fileName, String sha256) { }
}
