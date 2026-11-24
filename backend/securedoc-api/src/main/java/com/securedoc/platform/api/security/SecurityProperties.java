package com.securedoc.platform.api.security;

import com.documentplatform.identity.push.PushCallerSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "securedoc.security")
public class SecurityProperties {
    private SecurityMode mode = SecurityMode.OIDC;
    private final Oidc oidc = new Oidc();
    private final Cors cors = new Cors();

    public SecurityMode getMode() { return mode; }
    public void setMode(SecurityMode mode) { this.mode = mode == null ? SecurityMode.OIDC : mode; }
    public Oidc getOidc() { return oidc; }
    public Cors getCors() { return cors; }

    public static class Oidc {
        /** Task 0054: the claims only a person's token has; platform-identity's push-caller default, so the rule is the same. */
        static final java.util.List<String> DEFAULT_MACHINE_USER_CLAIMS =
                PushCallerSettings.DEFAULT_USER_CLAIMS.stream().sorted().toList();

        private String issuerUri = "";
        private String audience = "";
        private String browserClientId = "";
        private boolean persistentConnectionEnabled = true;
        private boolean sharedIssuerTenantSelectionEnabled = false;
        private String tenantId = "";
        private String tenantClaim = "tenant_id";
        private String usernameClaim = "preferred_username";
        private String rolesClaim = "groups";
        private String providerId = "customer-oidc";
        private String emailClaim = "email";
        private String displayNameClaim = "name";
        private final Map<String, String> roleMappings = new LinkedHashMap<>();
        private java.util.List<String> machineUserClaims = DEFAULT_MACHINE_USER_CLAIMS;

        public String getIssuerUri() { return issuerUri; }
        public void setIssuerUri(String issuerUri) { this.issuerUri = safe(issuerUri); }
        public String getAudience() { return audience; }
        public void setAudience(String audience) { this.audience = safe(audience); }
        public String getBrowserClientId() { return browserClientId; }
        public void setBrowserClientId(String browserClientId) { this.browserClientId = safe(browserClientId); }
        public boolean isPersistentConnectionEnabled() { return persistentConnectionEnabled; }
        public boolean isSharedIssuerTenantSelectionEnabled() { return sharedIssuerTenantSelectionEnabled; }
        public void setSharedIssuerTenantSelectionEnabled(boolean value) { sharedIssuerTenantSelectionEnabled = value; }
        public void setPersistentConnectionEnabled(boolean persistentConnectionEnabled) { this.persistentConnectionEnabled = persistentConnectionEnabled; }
        public String getTenantId() { return tenantId; }
        public void setTenantId(String tenantId) { this.tenantId = safe(tenantId); }
        public String getTenantClaim() { return tenantClaim; }
        public void setTenantClaim(String tenantClaim) { this.tenantClaim = safeOrDefault(tenantClaim, "tenant_id"); }
        public String getUsernameClaim() { return usernameClaim; }
        public void setUsernameClaim(String usernameClaim) { this.usernameClaim = safeOrDefault(usernameClaim, "preferred_username"); }
        public String getRolesClaim() { return rolesClaim; }
        public void setRolesClaim(String rolesClaim) { this.rolesClaim = safeOrDefault(rolesClaim, "groups"); }
        public String getProviderId() { return providerId; }
        public void setProviderId(String providerId) { this.providerId = safeOrDefault(providerId, "customer-oidc"); }
        public String getEmailClaim() { return emailClaim; }
        public void setEmailClaim(String emailClaim) { this.emailClaim = safeOrDefault(emailClaim, "email"); }
        public String getDisplayNameClaim() { return displayNameClaim; }
        public void setDisplayNameClaim(String displayNameClaim) { this.displayNameClaim = safeOrDefault(displayNameClaim, "name"); }
        public Map<String, String> getRoleMappings() { return roleMappings; }
        public java.util.List<String> getMachineUserClaims() { return machineUserClaims; }
        /** An empty list keeps the default: the check cannot be switched off, only told this login system's claims. */
        public void setMachineUserClaims(java.util.List<String> claims) {
            java.util.List<String> given = claims == null ? java.util.List.of()
                    : claims.stream().filter(c -> c != null && !c.isBlank()).map(String::trim).toList();
            this.machineUserClaims = given.isEmpty() ? DEFAULT_MACHINE_USER_CLAIMS : given;
        }

        private static String safe(String value) { return value == null ? "" : value.trim(); }
        private static String safeOrDefault(String value, String fallback) {
            String normalized = safe(value);
            return normalized.isEmpty() ? fallback : normalized;
        }
    }

    public static class Cors {
        private String allowedOrigins = "";

        public String getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(String allowedOrigins) { this.allowedOrigins = allowedOrigins == null ? "" : allowedOrigins.trim(); }
    }
}
