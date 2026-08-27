package com.securedoc.platform.domain.identity;

import java.util.Objects;
import java.util.UUID;

/**
 * Unique identifier for a vault entry.
 */
public record VaultEntryId(UUID value) {

    public VaultEntryId {
        Objects.requireNonNull(value, "VaultEntryId value must not be null");
    }

    public static VaultEntryId generate() {
        return new VaultEntryId(UUID.randomUUID());
    }

    public static VaultEntryId of(UUID value) {
        return new VaultEntryId(value);
    }

    public static VaultEntryId fromString(String value) {
        Objects.requireNonNull(value, "VaultEntryId string must not be null");
        return new VaultEntryId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
