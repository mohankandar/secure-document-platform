package com.securedoc.platform.api.security.oidc;

import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.api.security.SecurityProperties;
import com.securedoc.platform.application.port.out.TenantOidcGroupGrantRepository;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Finds tenant admissions from persisted ACTIVE connections and groups in a verified OIDC identity.
 * Not a replacement for the legacy OidcTenantResolver until tenant selection is implemented.
 */
@Component
public class OidcTenantMembershipResolver {
    private final TenantOidcGroupGrantRepository grants;
    private final TenantGroupAuthorization authorization = new TenantGroupAuthorization();
    private final TrustedTenantAdmission admission = new TrustedTenantAdmission();
    private final String providerId;
    private final String audience;

    public OidcTenantMembershipResolver(TenantOidcGroupGrantRepository grants,
                                        SecurityProperties securityProperties) {
        this.grants = Objects.requireNonNull(grants);
        this.providerId = securityProperties.getOidc().getProviderId();
        this.audience = securityProperties.getOidc().getAudience();
    }

    /** The caller must supply groups from normalized, cryptographically authenticated OIDC identity. */
    public Map<TenantId, Set<String>> authorizedTenants(Jwt verifiedJwt, Object authenticatedGroups) {
        if (verifiedJwt == null || verifiedJwt.getIssuer() == null
                || audience == null || audience.isBlank()) {
            throw new SecurityContextException("Verified OIDC tenant membership context is unavailable");
        }
        Map<TenantId, Map<String, Set<String>>> policies =
                grants.activeGrants(providerId, URI.create(verifiedJwt.getIssuer().toString()), audience);
        Map<TenantId, Set<String>> authorized = new LinkedHashMap<>();
        for (TenantId tenant : policies.keySet()) {
            try {
                authorized.put(tenant, authorization.authorizedRoles(tenant, authenticatedGroups, policies));
            } catch (SecurityContextException deniedForTenant) {
                // A user need not belong to every tenant exposed by the identity provider.
            }
        }
        return Map.copyOf(authorized);
    }

    public TenantId select(TenantId requestedTenant, Map<TenantId, Set<String>> authorizedTenants) {
        return admission.requireAuthorized(requestedTenant, authorizedTenants == null ? null : authorizedTenants.keySet());
    }

    public TenantId selectOnlyIfUnambiguous(Map<TenantId, Set<String>> authorizedTenants) {
        return admission.requireUnambiguous(authorizedTenants == null ? null : authorizedTenants.keySet());
    }
}
