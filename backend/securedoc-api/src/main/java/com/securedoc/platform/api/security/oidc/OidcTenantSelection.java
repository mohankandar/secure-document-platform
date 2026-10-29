package com.securedoc.platform.api.security.oidc;

import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.domain.identity.TenantId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Request-scoped selection only, never a tenant authorization source.
 * Every selected tenant must be present in the verified membership map.
 */
public final class OidcTenantSelection {
    private final TrustedTenantAdmission admission = new TrustedTenantAdmission();

    public TenantId select(String requestedTenant, Map<TenantId, Set<String>> memberships) {
        Set<TenantId> authorized = memberships == null ? null : memberships.keySet();
        if (requestedTenant == null || requestedTenant.isBlank()) {
            return admission.requireUnambiguous(authorized);
        }
        try {
            TenantId selected = TenantId.of(UUID.fromString(requestedTenant.trim()));
            return admission.requireAuthorized(selected, authorized);
        } catch (IllegalArgumentException invalid) {
            throw new SecurityContextException("Invalid selected tenant identifier");
        }
    }
}
