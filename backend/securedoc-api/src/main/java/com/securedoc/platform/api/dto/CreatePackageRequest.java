package com.securedoc.platform.api.dto;

import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/**
 * Manual "New case". {@code sensitivityLevel} is the optional case level chosen on New case (task 0021): read as raw
 * JSON so that no value makes the request fail; anything but an exact level name is taken as STANDARD (LV-INT-005).
 */
public record CreatePackageRequest(
        @NotBlank(message = "packageType is required") String packageType,
        Map<String,String> businessIdentifiers,
        Map<String,String> metadata,
        JsonNode sensitivityLevel,
        /* Task 0073: URGENT, NORMAL or LOW; optional. */
        String priority
) {
    public CreatePackageRequest {
        RequestValues.requireNoNullValues(businessIdentifiers, "businessIdentifiers");
        RequestValues.requireNoNullValues(metadata, "metadata");
        businessIdentifiers = businessIdentifiers == null ? Map.of() : Map.copyOf(businessIdentifiers);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public CreatePackageRequest(String packageType, Map<String,String> businessIdentifiers, Map<String,String> metadata) {
        this(packageType, businessIdentifiers, metadata, null, null);
    }

    /** The level as sent: null when absent or JSON null, the text of a string, otherwise its JSON form. */
    public String sentSensitivityLevel() {
        if (sensitivityLevel == null || sensitivityLevel.isNull() || sensitivityLevel.isMissingNode()) return null;
        return sensitivityLevel.isString() ? sensitivityLevel.stringValue() : sensitivityLevel.toString();
    }
}
