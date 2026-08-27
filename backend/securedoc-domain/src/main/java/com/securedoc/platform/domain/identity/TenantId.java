package com.securedoc.platform.domain.identity;

import java.util.Objects;
import java.util.UUID;

/**
 * Unique identifier for a tenant in the platform.
 */
public record TenantId(UUID value) {

    public TenantId {
        Objects.requireNonNull(value, "TenantId value must not be null");
    }

    public static TenantId generate() {
        return new TenantId(UUID.randomUUID());
    }

    public static TenantId of(UUID value) {
        return new TenantId(value);
    }

    public static TenantId fromString(String value) {
        Objects.requireNonNull(value, "TenantId string must not be null");
        return new TenantId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
