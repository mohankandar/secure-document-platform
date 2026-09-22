package com.securedoc.platform.application.branding;

import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.UUID;

/** Persisted deployment or tenant branding override. Null values inherit from the lower-precedence scope. */
public record BrandingConfiguration(
        UUID id,
        TenantId tenantId,
        String applicationName,
        String shortName,
        String organizationName,
        String pageTitle,
        String accentColor,
        String secondaryColor,
        byte[] primaryLogo,
        String primaryLogoMediaType,
        byte[] compactLogo,
        String compactLogoMediaType,
        byte[] favicon,
        String faviconMediaType,
        UUID updatedBy,
        Instant updatedAt
) {
    public boolean deploymentScope() {
        return tenantId == null;
    }
}
