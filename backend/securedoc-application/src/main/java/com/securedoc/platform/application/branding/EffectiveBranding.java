package com.securedoc.platform.application.branding;

import com.securedoc.platform.domain.identity.TenantId;

/** Fully resolved branding consumed by the UI. */
public record EffectiveBranding(
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
        String faviconMediaType
) {}
