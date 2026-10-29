package com.securedoc.platform.api.security.oidc;

import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class OidcMachineTenantAdmissionTest {
    private final OidcMachineTenantAdmission admission = new OidcMachineTenantAdmission();
    private static final TenantId A = TenantId.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final TenantId B = TenantId.of(UUID.fromString("00000000-0000-0000-0000-000000000002"));

    @Test void selectsOnlyUniqueBoundTenant() {
        assertEquals(A, admission.requireUnique(Set.of(A)));
    }

    @Test void deniesMissingOrAmbiguousBindings() {
        assertThrows(SecurityContextException.class, () -> admission.requireUnique(null));
        assertThrows(SecurityContextException.class, () -> admission.requireUnique(Set.of()));
        assertThrows(SecurityContextException.class, () -> admission.requireUnique(Set.of(A, B)));
        assertThrows(SecurityContextException.class, () -> admission.requireUnique(
                new java.util.HashSet<>(java.util.Collections.singletonList(null))));
    }
}
