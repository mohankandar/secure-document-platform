package com.securedoc.platform.api.security.oidc;

import com.documentplatform.identity.OidcConnectionBindingResolver;
import com.documentplatform.identity.spring.CachingOidcConnectionBindingResolver;
import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.api.security.SecurityProperties;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

/** Resolves human OIDC tenant from the persisted ACTIVE connection, with frozen config/legacy compatibility fallbacks. */
@Component
public class OidcTenantResolver {
    private final TenantId configuredTenantId;
    private final String legacyTenantClaim;
    private final String providerId;
    private final String audience;
    private final boolean persistentEnabled;
    private final OidcConnectionBindingResolver connections;

    public OidcTenantResolver(SecurityProperties properties, OidcConnectionBindingResolver connections) {
        this.configuredTenantId = parseConfiguredTenant(properties.getOidc().getTenantId());
        this.legacyTenantClaim = properties.getOidc().getTenantClaim();
        this.providerId = properties.getOidc().getProviderId();
        this.audience = properties.getOidc().getAudience();
        this.persistentEnabled = properties.getOidc().isPersistentConnectionEnabled();
        this.connections = new CachingOidcConnectionBindingResolver(connections, Duration.ofSeconds(30), 256);
    }

    public TenantId resolve(Jwt jwt) {
        if (persistentEnabled) {
            if (jwt.getIssuer() == null || audience.isBlank()) throw new SecurityContextException("Persistent OIDC connection lookup is not fully configured");
            var binding = connections.findActive(providerId, URI.create(jwt.getIssuer().toString()), audience);
            if (binding.isPresent()) return TenantId.of(binding.get().tenantId());
            throw new SecurityContextException("No ACTIVE persisted OIDC connection matches the authenticated issuer/provider/audience");
        }
        if (configuredTenantId != null) return configuredTenantId;
        String rawTenant = jwt.getClaimAsString(legacyTenantClaim);
        try { return TenantId.of(UUID.fromString(rawTenant == null ? "" : rawTenant.trim())); }
        catch (IllegalArgumentException ex) { throw new SecurityContextException("No trusted ACTIVE OIDC connection or valid compatibility tenant binding is available"); }
    }

    private TenantId parseConfiguredTenant(String rawTenant) {
        if (rawTenant == null || rawTenant.isBlank()) return null;
        try { return TenantId.of(UUID.fromString(rawTenant.trim())); }
        catch (IllegalArgumentException ex) { throw new IllegalStateException("securedoc.security.oidc.tenant-id must be a valid UUID", ex); }
    }
}
