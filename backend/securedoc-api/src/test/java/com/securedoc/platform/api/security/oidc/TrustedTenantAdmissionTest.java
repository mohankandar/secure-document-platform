package com.securedoc.platform.api.security.oidc;

import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TrustedTenantAdmissionTest {
    private static final TenantId A = TenantId.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final TenantId B = TenantId.of(UUID.fromString("00000000-0000-0000-0000-000000000002"));

    @Test void deniesUntrustedTenant() {
        TrustedTenantAdmission admission = new TrustedTenantAdmission();
        assertEquals(A, admission.requireAuthorized(A, Set.of(A)));
        assertThrows(SecurityContextException.class, () -> admission.requireAuthorized(B, Set.of(A)));
        assertThrows(SecurityContextException.class, () -> admission.requireAuthorized(A, Set.of()));
    }

    @Test void ambiguousCandidatesFailClosed() {
        TrustedTenantAdmission admission = new TrustedTenantAdmission();
        assertThrows(SecurityContextException.class, () -> admission.requireUnambiguous(Set.of(A, B)));
        assertEquals(B, admission.requireAuthorized(B, Set.of(A, B)));
    }
}
