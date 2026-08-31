package com.securedoc.platform.api.security;

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
        private String issuerUri = "";
        private String audience = "";
        private String tenantClaim = "tenant_id";
        private String usernameClaim = "preferred_username";
        private String rolesClaim = "groups";
        private final Map<String, String> roleMappings = new LinkedHashMap<>();

        public String getIssuerUri() { return issuerUri; }
        public void setIssuerUri(String issuerUri) { this.issuerUri = safe(issuerUri); }
        public String getAudience() { return audience; }
        public void setAudience(String audience) { this.audience = safe(audience); }
        public String getTenantClaim() { return tenantClaim; }
        public void setTenantClaim(String tenantClaim) { this.tenantClaim = safeOrDefault(tenantClaim, "tenant_id"); }
        public String getUsernameClaim() { return usernameClaim; }
        public void setUsernameClaim(String usernameClaim) { this.usernameClaim = safeOrDefault(usernameClaim, "preferred_username"); }
        public String getRolesClaim() { return rolesClaim; }
        public void setRolesClaim(String rolesClaim) { this.rolesClaim = safeOrDefault(rolesClaim, "groups"); }
        public Map<String, String> getRoleMappings() { return roleMappings; }

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
