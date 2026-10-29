package com.securedoc.platform.api.security.oidc;

import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TenantGroupAuthorizationTest {
    private static final TenantId A = TenantId.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final TenantId B = TenantId.of(UUID.fromString("00000000-0000-0000-0000-000000000002"));
    private final TenantGroupAuthorization policy = new TenantGroupAuthorization();

    @Test void rolesAreScopedToSelectedTenant() {
        var mappings = Map.of(
                A, Map.of("tenant-a-processors", Set.of("DOCUMENT_PROCESSOR")),
                B, Map.of("tenant-b-admins", Set.of("TENANT_IT_ADMIN")));
        assertEquals(Set.of("DOCUMENT_PROCESSOR"),
                policy.authorizedRoles(A, Set.of("tenant-a-processors", "tenant-b-admins"), mappings));
        assertEquals(Set.of("TENANT_IT_ADMIN"),
                policy.authorizedRoles(B, Set.of("tenant-a-processors", "tenant-b-admins"), mappings));
        assertThrows(SecurityContextException.class,
                () -> policy.authorizedRoles(B, Set.of("tenant-a-processors"), mappings));
    }

    @Test void rejectsMissingTenantPolicyAndAbsentGroups() {
        var mappings = Map.of(A, Map.of("a-group", Set.of("DOCUMENT_PROCESSOR")));
        assertThrows(SecurityContextException.class, () -> policy.authorizedRoles(B, Set.of("a-group"), mappings));
        assertThrows(SecurityContextException.class, () -> policy.authorizedRoles(A, null, mappings));
        assertThrows(SecurityContextException.class, () -> policy.authorizedRoles(A, Set.of(), mappings));
    }
}
