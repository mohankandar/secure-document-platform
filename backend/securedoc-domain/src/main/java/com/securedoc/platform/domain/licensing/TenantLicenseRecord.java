package com.securedoc.platform.domain.licensing;

import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable persisted copy of a signed tenant license token. */
public record TenantLicenseRecord(
        UUID id,
        TenantId tenantId,
        UUID licenseId,
        String signedToken,
        String payloadSha256,
        UUID installedBy,
        Instant installedAt
) {
    public TenantLicenseRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(licenseId, "licenseId");
        Objects.requireNonNull(signedToken, "signedToken");
        Objects.requireNonNull(payloadSha256, "payloadSha256");
        Objects.requireNonNull(installedBy, "installedBy");
        Objects.requireNonNull(installedAt, "installedAt");
    }
}
