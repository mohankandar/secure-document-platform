package com.securedoc.platform.domain.business;

import com.securedoc.platform.domain.identity.BusinessIdentifierDefinitionId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record BusinessIdentifierDefinition(
        BusinessIdentifierDefinitionId id,
        TenantId tenantId,
        String key,
        String label,
        boolean required,
        boolean primary,
        boolean searchable,
        boolean extractable,
        boolean displayable,
        int displayOrder,
        boolean active,
        BusinessIdentifierValidationType validationType,
        Integer exactLength,
        Integer minLength,
        Integer maxLength,
        BusinessIdentifierCaseNormalization caseNormalization,
        boolean stripSeparators,
        Set<String> allowedValues,
        String customPattern,
        Instant createdAt,
        Instant updatedAt
) {
    public BusinessIdentifierDefinition {
        Objects.requireNonNull(id);
        Objects.requireNonNull(tenantId);
        key = normalizeKey(key);
        label = requireText(label, "label");
        if (displayOrder < 0) throw new IllegalArgumentException("displayOrder must be >= 0");
        validationType = validationType == null ? BusinessIdentifierValidationType.TEXT : validationType;
        caseNormalization = caseNormalization == null ? BusinessIdentifierCaseNormalization.UPPER : caseNormalization;
        allowedValues = allowedValues == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(allowedValues));
        customPattern = validationType == BusinessIdentifierValidationType.CUSTOM_PATTERN
                && customPattern != null && !customPattern.isBlank() ? customPattern.trim() : null;
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(updatedAt);
        BusinessIdentifierValidator.validateConfiguration(validationType, exactLength, minLength, maxLength,
                allowedValues, customPattern);
    }

    /** Backward-compatible constructor for legacy callers/tests. */
    public BusinessIdentifierDefinition(BusinessIdentifierDefinitionId id, TenantId tenantId, String key, String label,
                                        boolean required, boolean primary, boolean searchable, boolean extractable,
                                        boolean displayable, int displayOrder, boolean active,
                                        Instant createdAt, Instant updatedAt) {
        this(id, tenantId, key, label, required, primary, searchable, extractable, displayable, displayOrder, active,
                BusinessIdentifierValidationType.TEXT, null, null, null,
                BusinessIdentifierCaseNormalization.UPPER, false, Set.of(), null, createdAt, updatedAt);
    }

    public static BusinessIdentifierDefinition create(TenantId tenantId, String key, String label,
            boolean required, boolean primary, boolean searchable, boolean extractable,
            boolean displayable, int displayOrder) {
        return create(tenantId, key, label, required, primary, searchable, extractable, displayable, displayOrder,
                BusinessIdentifierValidationType.TEXT, null, null, null,
                BusinessIdentifierCaseNormalization.UPPER, false, Set.of(), null);
    }

    public static BusinessIdentifierDefinition create(TenantId tenantId, String key, String label,
            boolean required, boolean primary, boolean searchable, boolean extractable,
            boolean displayable, int displayOrder, BusinessIdentifierValidationType validationType,
            Integer exactLength, Integer minLength, Integer maxLength,
            BusinessIdentifierCaseNormalization caseNormalization, boolean stripSeparators,
            Set<String> allowedValues, String customPattern) {
        Instant now = Instant.now();
        return new BusinessIdentifierDefinition(BusinessIdentifierDefinitionId.generate(), tenantId, key, label,
                required, primary, searchable, extractable, displayable, displayOrder, true,
                validationType, exactLength, minLength, maxLength, caseNormalization, stripSeparators,
                allowedValues, customPattern, now, now);
    }

    public BusinessIdentifierDefinition reconfigure(String newLabel, boolean newRequired, boolean newPrimary,
            boolean newSearchable, boolean newExtractable, boolean newDisplayable, int newDisplayOrder) {
        return reconfigure(newLabel, newRequired, newPrimary, newSearchable, newExtractable, newDisplayable,
                newDisplayOrder, validationType, exactLength, minLength, maxLength, caseNormalization,
                stripSeparators, allowedValues, customPattern);
    }

    public BusinessIdentifierDefinition reconfigure(String newLabel, boolean newRequired, boolean newPrimary,
            boolean newSearchable, boolean newExtractable, boolean newDisplayable, int newDisplayOrder,
            BusinessIdentifierValidationType newValidationType, Integer newExactLength, Integer newMinLength,
            Integer newMaxLength, BusinessIdentifierCaseNormalization newCaseNormalization,
            boolean newStripSeparators, Set<String> newAllowedValues, String newCustomPattern) {
        return new BusinessIdentifierDefinition(id, tenantId, key, newLabel, newRequired, newPrimary,
                newSearchable, newExtractable, newDisplayable, newDisplayOrder, true,
                newValidationType, newExactLength, newMinLength, newMaxLength, newCaseNormalization,
                newStripSeparators, newAllowedValues, newCustomPattern, createdAt, Instant.now());
    }

    public BusinessIdentifierDefinition deactivate() {
        if (!active) return this;
        return new BusinessIdentifierDefinition(id, tenantId, key, label, required, false, searchable,
                extractable, displayable, displayOrder, false, validationType, exactLength, minLength, maxLength,
                caseNormalization, stripSeparators, allowedValues, customPattern, createdAt, Instant.now());
    }

    public static String normalizeKey(String value) {
        String key = requireText(value, "key").trim().toLowerCase(Locale.ROOT);
        if (!key.matches("[a-z][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("identifier key must use lowercase letters, numbers, _ or -");
        }
        return key;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return trimmed;
    }
}
