package com.securedoc.platform.domain.event;

import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Event raised when a package is registered in the secure vault.
 */
public record PackageVaultedEvent(
        PackageId packageId,
        TenantId tenantId,
        UUID vaultEntryId,
        CorrelationId correlationId,
        Instant occurredAt
) implements DomainEvent {

    public PackageVaultedEvent {
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(vaultEntryId, "VaultEntryId must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
        Objects.requireNonNull(occurredAt, "OccurredAt must not be null");
    }

    public static PackageVaultedEvent of(PackageId packageId, TenantId tenantId,
                                          UUID vaultEntryId, CorrelationId correlationId) {
        return new PackageVaultedEvent(packageId, tenantId, vaultEntryId, correlationId, Instant.now());
    }

    @Override
    public String eventType() {
        return "PackageVaulted";
    }
}
