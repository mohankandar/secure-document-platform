package com.securedoc.platform.api.security;

import java.util.Set;
import java.util.UUID;

/**
 * Authorization boundary for future tenant role-assignment operations.
 * The identity provider remains authoritative; this policy does not modify identity groups.
 * Privileged grants remain disabled until a reviewed approval and revocation flow exists.
 */
public final class DelegatedRoleGrantPolicy {

    private static final Set<String> DELEGABLE_ROLES = Set.of(
            "DOCUMENT_PROCESSOR", "REPORT_VIEWER");

    public boolean mayAssign(Set<String> actorRoles, UUID actorId, UUID targetActorId,
                             UUID actorTenantId, UUID targetTenantId, String requestedRole) {
        if (actorRoles == null || actorId == null || targetActorId == null
                || actorTenantId == null || targetTenantId == null || requestedRole == null) {
            return false;
        }
        if (actorId.equals(targetActorId) || !actorTenantId.equals(targetTenantId)) {
            return false;
        }
        if (!actorRoles.contains("TENANT_IT_ADMIN")) {
            return false;
        }
        return DELEGABLE_ROLES.contains(requestedRole);
    }
}
