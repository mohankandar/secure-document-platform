package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.TenantId;
import java.net.URI;
import java.util.Map;
import java.util.Set;

/**
 * Server-controlled external identity group policy for ACTIVE tenant connections.
 * Caller identity and groups must come from a cryptographically verified IdP token.
 */
public interface TenantOidcGroupGrantRepository {
    Map<TenantId, Map<String, Set<String>>> activeGrants(String providerId, URI issuer, String audience);
}
