package com.securedoc.platform.api.security.oidc;

import com.documentplatform.identity.admission.AdmissionSettings;
import com.documentplatform.identity.admission.EntityMembershipResolver;
import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.api.security.SecurityProperties;
import com.securedoc.platform.application.port.out.ActiveOidcTenantCandidates;
import com.securedoc.platform.application.port.out.TenantOidcGroupGrantRepository;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.securedoc.platform.api.security.oidc.IdentityAdmissionMapping.refusingAsSecurityContext;
import static com.securedoc.platform.api.security.oidc.IdentityAdmissionMapping.toEntities;
import static com.securedoc.platform.api.security.oidc.IdentityAdmissionMapping.toEntity;
import static com.securedoc.platform.api.security.oidc.IdentityAdmissionMapping.toEntityKeys;
import static com.securedoc.platform.api.security.oidc.IdentityAdmissionMapping.toTenant;
import static com.securedoc.platform.api.security.oidc.IdentityAdmissionMapping.toTenantKeys;

/**
 * LexaVault's seam to platform-identity entity admission. The rules (an entity is admitted only with an
 * ACTIVE connection AND a matching verified group; roles from that entity's grants only) are the library's
 * {@link EntityMembershipResolver}. This class adapts LexaVault's lookups, ids, roles and refusals to it.
 */
@Component
public class OidcTenantMembershipResolver {

    private final EntityMembershipResolver admission;

    public OidcTenantMembershipResolver(TenantOidcGroupGrantRepository grants,
                                        ActiveOidcTenantCandidates candidates,
                                        SecurityProperties securityProperties) {
        Objects.requireNonNull(grants);
        Objects.requireNonNull(candidates);
        var oidc = securityProperties.getOidc();
        this.admission = new EntityMembershipResolver(
                key -> toEntityKeys(grants.activeGrants(key.providerId(), key.issuer(), key.audience())),
                key -> toEntities(candidates.findCandidates(key.providerId(), key.issuer(), key.audience())),
                new ProductEntityRoleCatalog(),
                new AdmissionSettings(oidc.isSharedIssuerTenantSelectionEnabled(), oidc.isPersistentConnectionEnabled(),
                        oidc.getProviderId(), oidc.getAudience()));
    }

    /** The caller must supply groups from normalized, cryptographically authenticated OIDC identity. */
    public Map<TenantId, Set<String>> authorizedTenants(Jwt verifiedJwt, Object authenticatedGroups) {
        if (verifiedJwt == null) {
            throw new SecurityContextException("Verified OIDC tenant membership context is unavailable", "MEMBERSHIP_CONTEXT_UNAVAILABLE");
        }
        URI issuer = verifiedJwt.getIssuer() == null ? null : URI.create(verifiedJwt.getIssuer().toString());
        return refusingAsSecurityContext(() -> Map.copyOf(toTenantKeys(admission.authorizedEntities(issuer, authenticatedGroups))));
    }

    public TenantId select(TenantId requestedTenant, Map<TenantId, Set<String>> authorizedTenants) {
        return refusingAsSecurityContext(() -> toTenant(admission.select(toEntity(requestedTenant), toEntityKeys(authorizedTenants))));
    }

    public TenantId selectOnlyIfUnambiguous(Map<TenantId, Set<String>> authorizedTenants) {
        return refusingAsSecurityContext(() -> toTenant(admission.selectOnlyIfUnambiguous(toEntityKeys(authorizedTenants))));
    }
}
