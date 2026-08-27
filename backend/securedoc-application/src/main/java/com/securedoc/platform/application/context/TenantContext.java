package com.securedoc.platform.application.context;

import com.securedoc.platform.domain.identity.TenantId;

import java.util.Objects;

/**
 * Provides the current tenant context for use case execution.
 * Every application service operation requires a resolved tenant context.
 */
public record TenantContext(TenantId tenantId, String tenantSlug) {

    public TenantContext {
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(tenantSlug, "Tenant slug must not be null");
        if (tenantSlug.isBlank()) {
            throw new IllegalArgumentException("Tenant slug must not be blank");
        }
    }

    public static TenantContext of(TenantId tenantId, String tenantSlug) {
        return new TenantContext(tenantId, tenantSlug);
    }
}
