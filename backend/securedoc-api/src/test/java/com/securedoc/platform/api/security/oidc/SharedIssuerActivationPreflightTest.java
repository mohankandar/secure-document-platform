package com.securedoc.platform.api.security.oidc;

import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SharedIssuerActivationPreflightTest {
    private static final TenantId A = TenantId.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final TenantId B = TenantId.of(UUID.fromString("00000000-0000-0000-0000-000000000002"));
    private final SharedIssuerActivationPreflight check = new SharedIssuerActivationPreflight();

    @Test void acceptsOnlyExplicitModeWithActiveAuthorizedTenants() {
        check.requireReady(true, true, Set.of(A, B),
                Map.of(A, Set.of("DOCUMENT_PROCESSOR"), B, Set.of("TENANT_IT_ADMIN")));
    }

    @Test void rejectsInactiveAndUnverifiedTenants() {
        assertThrows(SecurityContextException.class, () ->
                check.requireReady(true, true, Set.of(A), Map.of(B, Set.of("DOCUMENT_PROCESSOR"))));
        assertThrows(SecurityContextException.class, () ->
                check.requireReady(true, true, Set.of(A), Map.of()));
        assertThrows(SecurityContextException.class, () ->
                check.requireReady(false, true, Set.of(A), Map.of(A, Set.of("DOCUMENT_PROCESSOR"))));
        assertThrows(SecurityContextException.class, () ->
                check.requireReady(true, false, Set.of(A), Map.of(A, Set.of("DOCUMENT_PROCESSOR"))));
    }

    @Test void refusesPlatformRoleInTenantMembership() {
        assertThrows(SecurityContextException.class, () ->
                check.requireReady(true, true, Set.of(A), Map.of(A, Set.of("PLATFORM_OPERATIONS_ADMIN"))));
    }
}
