package com.securedoc.platform.domain.business;

import com.securedoc.platform.domain.identity.BusinessIdentifierDefinitionId;

import java.util.Locale;
import java.util.Objects;

public record BusinessIdentifier(
        BusinessIdentifierDefinitionId definitionId,
        String key,
        String label,
        String value,
        String normalizedValue,
        boolean primary,
        boolean searchable,
        boolean displayable,
        int displayOrder
) {
    public BusinessIdentifier {
        Objects.requireNonNull(definitionId);
        key = BusinessIdentifierDefinition.normalizeKey(key);
        label = requireText(label, "label");
        value = requireText(value, "value");
        normalizedValue = normalizeValue(normalizedValue == null ? value : normalizedValue);
    }

    public static BusinessIdentifier from(BusinessIdentifierDefinition definition, String value) {
        return new BusinessIdentifier(definition.id(), definition.key(), definition.label(), value,
                normalizeValue(value), definition.primary(), definition.searchable(),
                definition.displayable(), definition.displayOrder());
    }

    public static String normalizeValue(String value) {
        String trimmed = requireText(value, "value").replaceAll("\\s+", " ");
        return trimmed.toUpperCase(Locale.ROOT);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return trimmed;
    }
}
