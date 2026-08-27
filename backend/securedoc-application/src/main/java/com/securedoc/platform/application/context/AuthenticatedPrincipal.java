package com.securedoc.platform.application.context;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Objects;
import java.util.Set;

/**
 * Represents the currently authenticated user or system actor.
 * Framework-neutral — populated by the API/infrastructure layer before invoking use cases.
 */
public record AuthenticatedPrincipal(
        ActorId actorId,
        TenantId tenantId,
        String username,
        Set<String> roles
) {

    public AuthenticatedPrincipal {
        Objects.requireNonNull(actorId, "ActorId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(username, "Username must not be null");
        Objects.requireNonNull(roles, "Roles must not be null");
        if (username.isBlank()) {
            throw new IllegalArgumentException("Username must not be blank");
        }
        roles = Set.copyOf(roles);
    }

    public static AuthenticatedPrincipal of(ActorId actorId, TenantId tenantId,
                                             String username, Set<String> roles) {
        return new AuthenticatedPrincipal(actorId, tenantId, username, roles);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public TenantContext toTenantContext(String tenantSlug) {
        return TenantContext.of(tenantId, tenantSlug);
    }
}
