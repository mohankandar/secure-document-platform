package com.securedoc.platform.application.intake.polling;

import java.util.List;
import java.util.Map;

public record PollingSourcePackage(
        String externalSourcePackageId,
        String sourceRevision,
        boolean retry,
        Map<String,String> businessIdentifiers,
        Map<String,String> metadata,
        List<PollingSourceDocument> documents) {
    public PollingSourcePackage {
        businessIdentifiers = businessIdentifiers == null ? Map.of() : Map.copyOf(businessIdentifiers);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        documents = documents == null ? List.of() : List.copyOf(documents);
    }
}
