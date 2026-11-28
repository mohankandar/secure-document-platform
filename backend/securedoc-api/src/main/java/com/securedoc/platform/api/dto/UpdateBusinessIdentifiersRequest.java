package com.securedoc.platform.api.dto;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public record UpdateBusinessIdentifiersRequest(List<Item> identifiers) {
    public record Item(
            UUID id,
            String label,
            boolean required,
            boolean primary,
            boolean searchable,
            boolean extractable,
            boolean displayable,
            String validationType,
            Integer exactLength,
            Integer minLength,
            Integer maxLength,
            String caseNormalization,
            Boolean stripSeparators,
            Set<String> allowedValues,
            String customPattern,
            /* Task 0061. Null keeps the stored setting (a new field: what its name suggests). */
            String searchMode,
            Boolean sensitive,
            Boolean multiValue,
            String currency,
            String minValue,
            String maxValue,
            /* Task 0113 (U10): display names of the allowed values, by code; a value without one shows its code. */
            java.util.Map<String, String> allowedValueNames
    ) {}
}
