package com.securedoc.platform.api.security.oidc;

import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.domain.identity.TenantId;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit tenant admission check for future multi-tenant OIDC resolution.
 * Candidate discovery does not itself grant tenant access.
 *
 * This is deliberately independent of headers, tenant-claim strings and client-side state.
 * The caller must supply a server-verified set of permitted tenant IDs.
 */
public final class TrustedTenantAdmission {
    public TenantId requireAuthorized(TenantId requestedTenant, Set<TenantId> trustedMemberships) {
        Objects.requireNonNull(requestedTenant, "requestedTenant");
        if (trustedMemberships == null || !trustedMemberships.contains(requestedTenant)) {
            throw new SecurityContextException("Authenticated identity is not authorized for the requested tenant");
        }
        return requestedTenant;
    }

    /**
     * Selects a tenant only when exactly one is authorized. Ambiguous membership requires
     * an explicit request-scoped selection validated by requireAuthorized.
     */
    public TenantId requireUnambiguous(Set<TenantId> trustedMemberships) {
        if (trustedMemberships == null || trustedMemberships.size() != 1) {
            throw new SecurityContextException("A unique authorized tenant context is required");
        }
        return trustedMemberships.iterator().next();
    }
}
