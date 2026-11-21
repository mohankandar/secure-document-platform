package com.securedoc.platform.api.intake.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.securedoc.platform.application.intake.external.PushIntakeCreateInput;

import java.util.List;
import java.util.Map;

/**
 * Create request for the external push API and the scanner intake API.
 *
 * <p>{@code caseSensitivityLevel} and each document's {@code sensitivityLevel} are optional (task 0021). They are read
 * as raw JSON so that no value, not even a number or an object, makes the request fail: anything but an exact level
 * name is taken as STANDARD with the warning LV-INT-005.
 */
public record ExternalIntakeCreateRequest(
        String externalSourcePackageId,
        String sourceRevision,
        Map<String, String> businessIdentifiers,
        Map<String, String> metadata,
        List<ManifestDocument> documents,
        Boolean retry,
        JsonNode caseSensitivityLevel) {

    public record ManifestDocument(String externalSourceDocumentId, String fileName, String sha256,
                                   JsonNode sensitivityLevel) { }

    PushIntakeCreateInput toInput() {
        List<ManifestDocument> docs = documents == null ? List.of() : documents;
        return new PushIntakeCreateInput(externalSourcePackageId, sourceRevision, businessIdentifiers, metadata,
                docs.stream().map(d -> new PushIntakeCreateInput.ManifestDocumentInput(
                        d.externalSourceDocumentId(), d.fileName(), d.sha256(), sent(d.sensitivityLevel()))).toList(),
                Boolean.TRUE.equals(retry), sent(caseSensitivityLevel));
    }

    /** The value as sent: null when absent or JSON null, the text of a string, otherwise its JSON form (never a level). */
    static String sent(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return null;
        return value.isTextual() ? value.textValue() : value.toString();
    }
}
