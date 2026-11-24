package com.securedoc.platform.api.security.oidc;

import com.documentplatform.identity.admission.AdmissionSettings;
import com.documentplatform.identity.admission.ConnectionKey;
import com.documentplatform.identity.admission.EntityMembershipResolver;
import com.documentplatform.identity.admission.EntityRoleCatalog;
import com.documentplatform.identity.admission.IdentityAdmissionException.Reason;
import com.documentplatform.identity.admission.PlatformGroupAuthorization;
import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.api.security.ProductRole;
import com.securedoc.platform.api.security.SecurityProperties;
import com.securedoc.platform.application.port.out.ActiveOidcTenantCandidates;
import com.securedoc.platform.application.port.out.PlatformOidcGroupGrants;
import com.securedoc.platform.application.port.out.TenantOidcGroupGrantRepository;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

    /** A platform-wide grant's role counts only when written exactly as LexaVault's canonical role name. */
    private static final EntityRoleCatalog EXACT_ROLES = new EntityRoleCatalog() {
        @Override
        public Optional<String> canonicalEntityRole(String configuredName) {
            return Arrays.stream(ProductRole.values()).map(Enum::name).filter(n -> n.equals(configuredName)).findFirst();
        }

        @Override
        public boolean isPlatformRole(String canonicalRole) {
            return ProductRole.PLATFORM_OPERATIONS_ADMIN.name().equals(canonicalRole);
        }
    };

    private final EntityMembershipResolver admission;
    private final PlatformGroupAuthorization platformAuthorization;
    private final ActiveOidcTenantCandidates candidates;
    private final String providerId;
    private final String audience;

    /** For tests without platform-wide grants. */
    public OidcTenantMembershipResolver(TenantOidcGroupGrantRepository grants,
                                        ActiveOidcTenantCandidates candidates,
                                        SecurityProperties securityProperties) {
        this(grants, candidates, (provider, issuer, audience) -> Map.of(), securityProperties);
    }

    @Autowired
    public OidcTenantMembershipResolver(TenantOidcGroupGrantRepository grants,
                                        ActiveOidcTenantCandidates candidates,
                                        PlatformOidcGroupGrants platformGrants,
                                        SecurityProperties securityProperties) {
        Objects.requireNonNull(grants);
        this.candidates = Objects.requireNonNull(candidates);
        Objects.requireNonNull(platformGrants);
        this.providerId = securityProperties.getOidc().getProviderId();
        this.audience = securityProperties.getOidc().getAudience();
        this.platformAuthorization = new PlatformGroupAuthorization(EXACT_ROLES,
                key -> platformGrants.activePlatformGrants(key.providerId(), key.issuer(), key.audience()));
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
        URI issuer = issuer(verifiedJwt);
        Map<TenantId, Set<String>> byEntityGrants = refusingAsSecurityContext(
                () -> toTenantKeys(admission.authorizedEntities(issuer, authenticatedGroups)));
        Set<String> platformRoles = platformRoles(verifiedJwt, authenticatedGroups);
        if (platformRoles.isEmpty()) return Map.copyOf(byEntityGrants);
        // Task 0054: a platform-wide grant is for every ACTIVE entity of the customer, on top of the entity's own grants.
        Set<TenantId> entities = candidates.findCandidates(providerId, issuer, audience);
        if (entities == null) {
            throw new SecurityContextException(IdentityAdmissionMapping.message(Reason.POLICY_UNAVAILABLE),
                    Reason.POLICY_UNAVAILABLE.name());
        }
        Map<TenantId, Set<String>> out = new LinkedHashMap<>(byEntityGrants);
        for (TenantId entity : entities) {
            Set<String> roles = new LinkedHashSet<>(byEntityGrants.getOrDefault(entity, Set.of()));
            roles.addAll(platformRoles);
            out.put(entity, Set.copyOf(roles));
        }
        return Map.copyOf(out);
    }

    /**
     * The platform role the verified groups have through the customer's platform-wide grants (task 0054; platform-identity's
     * PlatformGroupAuthorization), or none. A grant of an entity role or an unknown name to one of the groups refuses, and
     * so do grants that cannot be read. The caller supplies groups from the verified token.
     */
    public Set<String> platformRoles(Jwt verifiedJwt, Object authenticatedGroups) {
        URI issuer = verifiedJwt == null ? null : issuer(verifiedJwt);
        if (issuer == null || audience.isBlank()) {
            throw new SecurityContextException("Verified OIDC tenant membership context is unavailable", "MEMBERSHIP_CONTEXT_UNAVAILABLE");
        }
        return refusingAsSecurityContext(() -> platformAuthorization.platformRoles(
                new ConnectionKey(providerId, issuer, audience), authenticatedGroups));
    }

    private static URI issuer(Jwt jwt) {
        return jwt.getIssuer() == null ? null : URI.create(jwt.getIssuer().toString());
    }

    public TenantId select(TenantId requestedTenant, Map<TenantId, Set<String>> authorizedTenants) {
        return refusingAsSecurityContext(() -> toTenant(admission.select(toEntity(requestedTenant), toEntityKeys(authorizedTenants))));
    }

    public TenantId selectOnlyIfUnambiguous(Map<TenantId, Set<String>> authorizedTenants) {
        return refusingAsSecurityContext(() -> toTenant(admission.selectOnlyIfUnambiguous(toEntityKeys(authorizedTenants))));
    }
}
