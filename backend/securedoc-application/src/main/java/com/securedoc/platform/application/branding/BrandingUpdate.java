package com.securedoc.platform.application.branding;

/** Safe administrative branding payload. Binary assets are already decoded and validated by the service. */
public record BrandingUpdate(
        String applicationName,
        String shortName,
        String organizationName,
        String pageTitle,
        String accentColor,
        String secondaryColor,
        BrandingAssetUpdate primaryLogo,
        BrandingAssetUpdate compactLogo,
        BrandingAssetUpdate favicon
) {
    public record BrandingAssetUpdate(byte[] bytes, String mediaType, boolean clear) {
        public static BrandingAssetUpdate unchanged() { return null; }
    }
}
