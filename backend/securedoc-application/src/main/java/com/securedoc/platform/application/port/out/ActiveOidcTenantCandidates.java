package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.TenantId;
import java.net.URI;
import java.util.Set;

/**
 * Lists candidate tenants with ACTIVE OIDC bindings.
 * Candidates are not memberships; callers must independently verify
 * the authenticated identity against tenant-specific admission policy.
 */
public interface ActiveOidcTenantCandidates {
    Set<TenantId> findCandidates(String providerId, URI issuer, String audience);
}
