package com.securedoc.platform.domain.business;

import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Tenant-owned business policy for what to redact and when review is required. */
public record TenantRedactionPolicy(
        TenantId tenantId,
        Set<EntityType> enabledEntityTypes,
        double autoRedactThreshold,
        ReviewMode reviewMode,
        boolean maskSsnLastFour,
        Instant updatedAt
) {
    private static final Set<EntityType> V1_CONFIGURABLE_TYPES = Set.of(
            EntityType.SSN,
            EntityType.DATE_OF_BIRTH,
            EntityType.CREDIT_CARD_NUMBER,
            EntityType.CREDIT_SCORE
    );

    public TenantRedactionPolicy {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(enabledEntityTypes, "enabledEntityTypes must not be null");
        Objects.requireNonNull(reviewMode, "reviewMode must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (autoRedactThreshold < 0.0 || autoRedactThreshold > 1.0) {
            throw new IllegalArgumentException("autoRedactThreshold must be between 0 and 1");
        }
        if (!V1_CONFIGURABLE_TYPES.containsAll(enabledEntityTypes)) {
            throw new IllegalArgumentException("Policy contains entity types that are not configurable in V1");
        }
        enabledEntityTypes = Set.copyOf(enabledEntityTypes);
    }

    public static TenantRedactionPolicy defaults(TenantId tenantId) {
        return new TenantRedactionPolicy(
                tenantId,
                EnumSet.copyOf(V1_CONFIGURABLE_TYPES),
                0.80,
                ReviewMode.UNCERTAIN_ONLY,
                true,
                Instant.now());
    }

    public static Set<EntityType> v1ConfigurableTypes() {
        return V1_CONFIGURABLE_TYPES;
    }

    public boolean isEnabled(EntityType type) {
        return enabledEntityTypes.contains(type);
    }
}
