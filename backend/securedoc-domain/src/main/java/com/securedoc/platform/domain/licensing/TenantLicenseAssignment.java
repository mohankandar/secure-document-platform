package com.securedoc.platform.domain.licensing;

import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.UUID;

/** Points a tenant at its currently installed license record while retaining prior records as history. */
public record TenantLicenseAssignment(
        TenantId tenantId,
        UUID licenseRecordId,
        UUID activatedBy,
        Instant activatedAt
) {}
