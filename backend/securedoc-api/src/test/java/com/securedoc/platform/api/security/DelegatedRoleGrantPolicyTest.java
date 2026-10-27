package com.securedoc.platform.api.security;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DelegatedRoleGrantPolicyTest {
    private final DelegatedRoleGrantPolicy policy = new DelegatedRoleGrantPolicy();
    private final UUID actor = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();
    private final UUID tenant = UUID.randomUUID();

    @Test
    void tenantItMayDelegateOnlyLowPrivilegeRolesWithinTenant() {
        assertTrue(policy.mayAssign(Set.of("TENANT_IT_ADMIN"), actor, target, tenant, tenant, "DOCUMENT_PROCESSOR"));
        assertTrue(policy.mayAssign(Set.of("TENANT_IT_ADMIN"), actor, target, tenant, tenant, "REPORT_VIEWER"));
    }

    @Test
    void blocksSelfElevationAndCrossTenantAssignments() {
        assertFalse(policy.mayAssign(Set.of("TENANT_IT_ADMIN"), actor, actor, tenant, tenant, "REPORT_VIEWER"));
        assertFalse(policy.mayAssign(Set.of("TENANT_IT_ADMIN"), actor, target, tenant, UUID.randomUUID(), "REPORT_VIEWER"));
    }

    @Test
    void blocksPrivilegedRolesAndLegacyAdmin() {
        for (String role : Set.of("ADMIN", "PLATFORM_OPERATIONS_ADMIN", "TENANT_IT_ADMIN",
                "BUSINESS_ADMIN", "DOCUMENT_SUPERVISOR")) {
            assertFalse(policy.mayAssign(Set.of("TENANT_IT_ADMIN"), actor, target, tenant, tenant, role), role);
        }
    }

    @Test
    void rejectsUnprivilegedActorAndMissingInformation() {
        assertFalse(policy.mayAssign(Set.of("BUSINESS_ADMIN"), actor, target, tenant, tenant, "DOCUMENT_PROCESSOR"));
        assertFalse(policy.mayAssign(null, actor, target, tenant, tenant, "DOCUMENT_PROCESSOR"));
        assertFalse(policy.mayAssign(Set.of("TENANT_IT_ADMIN"), actor, target, tenant, tenant, null));
    }
}
