package com.securedoc.platform.api.security.oidc;

import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.domain.identity.TenantId;
import java.util.Map;
import java.util.Set;

/**
 * Deployment preflight for enabling the shared-issuer identity selection mode.
 * It is a configuration guard only: database migration and identity activation
 * must be validated separately before the feature is enabled.
 */
public final class SharedIssuerActivationPreflight {
    public void requireReady(boolean persistedConnections, boolean explicitlyEnabled,
                             Set<TenantId> activeCandidates,
                             Map<TenantId, Set<String>> verifiedMemberships) {
        if (!persistedConnections || !explicitlyEnabled) {
            throw new SecurityContextException("Shared-issuer tenant selection is not enabled");
        }
        if (activeCandidates == null || activeCandidates.isEmpty()
                || verifiedMemberships == null || verifiedMemberships.isEmpty()) {
            throw new SecurityContextException("No verified ACTIVE tenant membership is available");
        }
        if (!activeCandidates.containsAll(verifiedMemberships.keySet())) {
            throw new SecurityContextException("Tenant membership is not backed by ACTIVE identity connections");
        }
        for (Set<String> roles : verifiedMemberships.values()) {
            if (roles == null || roles.isEmpty() || roles.contains("PLATFORM_OPERATIONS_ADMIN")) {
                throw new SecurityContextException("Invalid tenant-scoped role grant");
            }
        }
    }
}
