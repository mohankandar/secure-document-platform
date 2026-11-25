package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.TenantId;
import java.net.URI;
import java.util.Set;

/** Candidates only: calling code MUST reject zero or multiple tenant matches. */
public interface ActiveOidcMachineTenantBindings {
    Set<TenantId> findEnabledPushTenants(String providerId, URI issuer, String audience, String clientPrincipal);

    /** Task 0075: the same for an enabled scanner source. Nobody, unless the store says otherwise. */
    default Set<TenantId> findEnabledScannerTenants(String providerId, URI issuer, String audience, String clientPrincipal) {
        return Set.of();
    }
}
