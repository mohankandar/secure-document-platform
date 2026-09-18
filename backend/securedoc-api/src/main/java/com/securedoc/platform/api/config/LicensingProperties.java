package com.securedoc.platform.api.config;

/** Runtime licensing configuration. Client installs should not need to edit this directly. */
public class LicensingProperties {
    public enum Enforcement { ENFORCED, DISABLED }

    private Enforcement enforcement = Enforcement.ENFORCED;
    private String verificationKeyResource = "classpath:licensing/lexavault-license-public-key.pem";
    private String developmentPublicKey = "";
    private boolean allowDevelopmentKeyOverride;

    public Enforcement getEnforcement() { return enforcement; }
    public void setEnforcement(Enforcement enforcement) { this.enforcement = enforcement; }
    public String getVerificationKeyResource() { return verificationKeyResource; }
    public void setVerificationKeyResource(String verificationKeyResource) { this.verificationKeyResource = verificationKeyResource; }
    public String getDevelopmentPublicKey() { return developmentPublicKey; }
    public void setDevelopmentPublicKey(String developmentPublicKey) { this.developmentPublicKey = developmentPublicKey; }
    public boolean isAllowDevelopmentKeyOverride() { return allowDevelopmentKeyOverride; }
    public void setAllowDevelopmentKeyOverride(boolean allowDevelopmentKeyOverride) { this.allowDevelopmentKeyOverride = allowDevelopmentKeyOverride; }
    public boolean enforcementEnabled() { return enforcement == Enforcement.ENFORCED; }
}
