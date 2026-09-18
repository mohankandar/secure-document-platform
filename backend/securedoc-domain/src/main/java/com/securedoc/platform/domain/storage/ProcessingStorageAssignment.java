package com.securedoc.platform.domain.storage;

import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Active storage assignment. Null tenantId means deployment default; otherwise it is a tenant override. */
public record ProcessingStorageAssignment(
        UUID id,
        TenantId tenantId,
        UUID providerId,
        UUID activatedBy,
        Instant activatedAt) {
    public ProcessingStorageAssignment {
        Objects.requireNonNull(id, "Assignment id is required");
        Objects.requireNonNull(providerId, "Provider id is required");
        Objects.requireNonNull(activatedAt, "Activation time is required");
    }

    public boolean deploymentDefault() { return tenantId == null; }
}
