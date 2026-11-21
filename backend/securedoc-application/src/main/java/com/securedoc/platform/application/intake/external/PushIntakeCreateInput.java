package com.securedoc.platform.application.intake.external;

import java.util.List;
import java.util.Map;

/**
 * One machine intake request. {@code caseSensitivityLevel} and each document's {@code sensitivityLevel} are the raw
 * values the source sent (task 0021), or null when it sent none; the service reads them with
 * {@link com.securedoc.platform.domain.document.SensitivityLevel#fromSource} and never refuses an unknown one.
 */
public record PushIntakeCreateInput(
        String externalSourcePackageId,
        String sourceRevision,
        Map<String, String> businessIdentifiers,
        Map<String, String> metadata,
        List<ManifestDocumentInput> documents,
        boolean retry,
        String caseSensitivityLevel) {
    public PushIntakeCreateInput {
        // Task 0023 D1 (#32): a null value is the source's mistake (400), not a NullPointerException (500).
        requireNoNull(businessIdentifiers == null ? null : businessIdentifiers.values(),
                "businessIdentifiers must not contain null values");
        requireNoNull(metadata == null ? null : metadata.values(), "metadata must not contain null values");
        requireNoNull(documents, "documents must not contain null entries");
        businessIdentifiers = businessIdentifiers == null ? Map.of() : Map.copyOf(businessIdentifiers);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        documents = documents == null ? List.of() : List.copyOf(documents);
    }

    public PushIntakeCreateInput(String externalSourcePackageId, String sourceRevision,
                                 Map<String, String> businessIdentifiers, Map<String, String> metadata,
                                 List<ManifestDocumentInput> documents, boolean retry) {
        this(externalSourcePackageId, sourceRevision, businessIdentifiers, metadata, documents, retry, null);
    }

    public PushIntakeCreateInput(String externalSourcePackageId, String sourceRevision,
                                 Map<String, String> businessIdentifiers, Map<String, String> metadata,
                                 List<ManifestDocumentInput> documents) {
        this(externalSourcePackageId, sourceRevision, businessIdentifiers, metadata, documents, false, null);
    }

    private static void requireNoNull(java.util.Collection<?> values, String message) {
        if (values != null && values.stream().anyMatch(java.util.Objects::isNull)) {
            throw new com.securedoc.platform.application.exception.ValidationException(message);
        }
    }

    public record ManifestDocumentInput(String externalSourceDocumentId, String fileName, String sha256,
                                        String sensitivityLevel) {
        public ManifestDocumentInput(String externalSourceDocumentId, String fileName, String sha256) {
            this(externalSourceDocumentId, fileName, sha256, null);
        }
    }
}
