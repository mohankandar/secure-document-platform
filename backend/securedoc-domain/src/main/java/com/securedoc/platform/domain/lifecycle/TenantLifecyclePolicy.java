package com.securedoc.platform.domain.lifecycle;

import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.Objects;

/**
 * Tenant-owned lifecycle policy for temporary internal artifacts and final-delivery approval.
 * Customer-delivered redacted output is durable at the configured destination; internal artifacts are temporary.
 */
public record TenantLifecyclePolicy(
        TenantId tenantId,
        int originalRetentionHours,
        int redactedCacheHours,
        int neverExportedHoldHours,
        boolean finalApprovalRequired,
        Instant updatedAt
) {
    public static final int DEFAULT_ORIGINAL_RETENTION_HOURS = 24;
    public static final int DEFAULT_REDACTED_CACHE_HOURS = 24;
    public static final int DEFAULT_NEVER_EXPORTED_HOLD_HOURS = 72;
    public static final int MAX_REDACTED_CACHE_HOURS = 72;
    public static final int MAX_NEVER_EXPORTED_HOLD_HOURS = 14 * 24;
    public static final int MAX_SUCCESSFUL_EXPORT_CYCLES = 3;

    public TenantLifecyclePolicy {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (originalRetentionHours < 1) {
            throw new IllegalArgumentException("originalRetentionHours must be at least 1");
        }
        if (redactedCacheHours < 1 || redactedCacheHours > MAX_REDACTED_CACHE_HOURS) {
            throw new IllegalArgumentException("redactedCacheHours must be between 1 and " + MAX_REDACTED_CACHE_HOURS);
        }
        if (neverExportedHoldHours < DEFAULT_NEVER_EXPORTED_HOLD_HOURS
                || neverExportedHoldHours > MAX_NEVER_EXPORTED_HOLD_HOURS) {
            throw new IllegalArgumentException("neverExportedHoldHours must be between "
                    + DEFAULT_NEVER_EXPORTED_HOLD_HOURS + " and " + MAX_NEVER_EXPORTED_HOLD_HOURS);
        }
    }

    public static TenantLifecyclePolicy defaults(TenantId tenantId) {
        return new TenantLifecyclePolicy(
                tenantId,
                DEFAULT_ORIGINAL_RETENTION_HOURS,
                DEFAULT_REDACTED_CACHE_HOURS,
                DEFAULT_NEVER_EXPORTED_HOLD_HOURS,
                false,
                Instant.now());
    }
}
