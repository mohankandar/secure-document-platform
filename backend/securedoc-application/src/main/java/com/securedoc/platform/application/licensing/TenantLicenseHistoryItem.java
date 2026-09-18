package com.securedoc.platform.application.licensing;

import com.securedoc.platform.domain.licensing.LicenseClaims;

import java.time.Instant;
import java.util.UUID;

public record TenantLicenseHistoryItem(
        UUID recordId,
        UUID licenseId,
        Instant installedAt,
        UUID installedBy,
        boolean active,
        String status,
        LicenseClaims claims,
        String reasonCode,
        String verificationEvidence
) {}
