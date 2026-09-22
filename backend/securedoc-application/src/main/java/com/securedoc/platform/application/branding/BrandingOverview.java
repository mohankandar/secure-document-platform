package com.securedoc.platform.application.branding;

import java.util.List;

public record BrandingOverview(
        BrandingConfiguration deployment,
        List<TenantBrandingSummary> tenants
) {
    public record TenantBrandingSummary(String tenantId, String tenantName, String tenantSlug,
                                        boolean customBranding) {}
}
