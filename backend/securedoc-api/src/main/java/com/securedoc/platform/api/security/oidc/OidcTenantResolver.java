package com.securedoc.platform.api.security.oidc;

import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.api.security.SecurityProperties;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Resolves the internal LexaVault tenant from the configured OIDC connection.
 *
 * The customer IdP is not required to know or emit LexaVault's internal tenant UUID.
 * A configured tenant-id is parsed once at startup and reused for every request.
 * The token tenant claim is retained only as a backward-compatible fallback for
 * existing deployments that have not migrated to connection-owned tenant mapping.
 */
@Component
public class OidcTenantResolver {
    private final TenantId configuredTenantId;
    private final String legacyTenantClaim;

    public OidcTenantResolver(SecurityProperties properties) {
        this.configuredTenantId = parseConfiguredTenant(properties.getOidc().getTenantId());
        this.legacyTenantClaim = properties.getOidc().getTenantClaim();
    }

    public TenantId resolve(Jwt jwt) {
        if (configuredTenantId != null) {
            return configuredTenantId;
        }

        String rawTenant = jwt.getClaimAsString(legacyTenantClaim);
        try {
            return TenantId.of(UUID.fromString(rawTenant == null ? "" : rawTenant.trim()));
        } catch (IllegalArgumentException ex) {
            throw new SecurityContextException(
                    "OIDC connection has no configured tenant and authenticated identity has no valid legacy tenant claim");
        }
    }

    private TenantId parseConfiguredTenant(String rawTenant) {
        if (rawTenant == null || rawTenant.isBlank()) {
            return null;
        }
        try {
            return TenantId.of(UUID.fromString(rawTenant.trim()));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("securedoc.security.oidc.tenant-id must be a valid UUID", ex);
        }
    }
}
