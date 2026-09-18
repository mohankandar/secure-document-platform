package com.securedoc.platform.domain.licensing;

import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Claims covered by the vendor signature. The signed payload is the source of truth. */
public record LicenseClaims(
        int schemaVersion,
        UUID licenseId,
        TenantId tenantId,
        String customerName,
        String edition,
        Instant issuedAt,
        Instant notBefore,
        Instant expiresAt,
        Set<String> entitlements,
        Map<String, Long> limits
) {
    public LicenseClaims {
        if (schemaVersion <= 0) throw new IllegalArgumentException("schemaVersion must be positive");
        Objects.requireNonNull(licenseId, "licenseId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(edition, "edition");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        entitlements = entitlements == null ? Set.of() : Set.copyOf(entitlements);
        limits = limits == null ? Map.of() : Map.copyOf(limits);
    }

    public boolean hasEntitlement(LicenseEntitlement entitlement) {
        return entitlements.contains(entitlement.name());
    }
}
