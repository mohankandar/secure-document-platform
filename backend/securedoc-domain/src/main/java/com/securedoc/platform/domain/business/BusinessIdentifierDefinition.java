package com.securedoc.platform.domain.business;

import com.securedoc.platform.domain.identity.BusinessIdentifierDefinitionId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

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
        Instant createdAt,
        Instant updatedAt
) {
    public BusinessIdentifierDefinition {
        Objects.requireNonNull(id);
        Objects.requireNonNull(tenantId);
        key = normalizeKey(key);
        label = requireText(label, "label");
        if (displayOrder < 0) throw new IllegalArgumentException("displayOrder must be >= 0");
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(updatedAt);
    }

    public static BusinessIdentifierDefinition create(TenantId tenantId, String key, String label,
            boolean required, boolean primary, boolean searchable, boolean extractable,
            boolean displayable, int displayOrder) {
        Instant now = Instant.now();
        return new BusinessIdentifierDefinition(BusinessIdentifierDefinitionId.generate(), tenantId, key, label,
                required, primary, searchable, extractable, displayable, displayOrder, true, now, now);
    }

    public BusinessIdentifierDefinition reconfigure(String newLabel, boolean newRequired, boolean newPrimary,
            boolean newSearchable, boolean newExtractable, boolean newDisplayable, int newDisplayOrder) {
        return new BusinessIdentifierDefinition(id, tenantId, key, newLabel, newRequired, newPrimary,
                newSearchable, newExtractable, newDisplayable, newDisplayOrder, true, createdAt, Instant.now());
    }

    public BusinessIdentifierDefinition deactivate() {
        if (!active) return this;
        return new BusinessIdentifierDefinition(id, tenantId, key, label, required, false, searchable,
                extractable, displayable, displayOrder, false, createdAt, Instant.now());
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
