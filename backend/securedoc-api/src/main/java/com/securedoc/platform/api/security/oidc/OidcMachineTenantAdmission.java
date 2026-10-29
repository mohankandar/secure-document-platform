package com.securedoc.platform.api.security.oidc;

import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.domain.identity.TenantId;
import java.util.Set;

/** A machine identity must be bound to exactly one ACTIVE tenant integration. */
public final class OidcMachineTenantAdmission {
    public TenantId requireUnique(Set<TenantId> candidates) {
        if (candidates == null || candidates.size() != 1) {
            throw new SecurityContextException("Machine identity has no unique ACTIVE tenant integration binding");
        }
        TenantId selected = candidates.iterator().next();
        if (selected == null) {
            throw new SecurityContextException("Machine identity has no unique ACTIVE tenant integration binding");
        }
        return selected;
    }
}
