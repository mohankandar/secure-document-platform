package com.securedoc.platform.application.licensing;

import java.util.List;

public record LicensingOverview(
        boolean enforcementEnabled,
        boolean verificationKeyConfigured,
        List<TenantLicenseStatus> tenants
) {}
