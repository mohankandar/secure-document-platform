package com.securedoc.platform.application.licensing;

import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.licensing.LicenseClaims;
import com.securedoc.platform.domain.licensing.LicenseValidationStatus;

import java.util.UUID;

/** Effective licensing state returned to platform administration and enforcement callers. */
public record TenantLicenseStatus(
        TenantId tenantId,
        String tenantName,
        String tenantSlug,
        LicenseValidationStatus status,
        boolean processingAllowed,
        UUID licenseRecordId,
        LicenseClaims claims,
        String reasonCode
) {}
