package com.securedoc.platform.application.intake.polling;

import java.util.List;
import java.util.Map;

/** One package a polling source offers. {@code caseSensitivityLevel} is the raw value it sent, or null (task 0021). */
public record PollingSourcePackage(
        String externalSourcePackageId,
        String sourceRevision,
        boolean retry,
        Map<String,String> businessIdentifiers,
        Map<String,String> metadata,
        List<PollingSourceDocument> documents,
        String caseSensitivityLevel,
        /* Task 0073: as sent, or null. */
        String priority) {
    /** Task 0061: case field values are in no text form of this. */
    @Override
    public String toString() {
        return "PollingSourcePackage[" + "fields=" + businessIdentifiers.size() + "]";
    }

    public PollingSourcePackage {
        businessIdentifiers = businessIdentifiers == null ? Map.of() : Map.copyOf(businessIdentifiers);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        documents = documents == null ? List.of() : List.copyOf(documents);
    }

    public PollingSourcePackage(String externalSourcePackageId, String sourceRevision, boolean retry,
                                Map<String,String> businessIdentifiers, Map<String,String> metadata,
                                List<PollingSourceDocument> documents, String caseSensitivityLevel) {
        this(externalSourcePackageId, sourceRevision, retry, businessIdentifiers, metadata, documents,
                caseSensitivityLevel, null);
    }

    public PollingSourcePackage(String externalSourcePackageId, String sourceRevision, boolean retry,
                                Map<String,String> businessIdentifiers, Map<String,String> metadata,
                                List<PollingSourceDocument> documents) {
        this(externalSourcePackageId, sourceRevision, retry, businessIdentifiers, metadata, documents, null);
    }
}
