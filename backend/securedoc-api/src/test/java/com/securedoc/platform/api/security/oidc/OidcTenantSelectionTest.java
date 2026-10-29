package com.securedoc.platform.api.security.oidc;

import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class OidcTenantSelectionTest {
    private final OidcTenantSelection selector = new OidcTenantSelection();
    private static final TenantId A = TenantId.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final TenantId B = TenantId.of(UUID.fromString("00000000-0000-0000-0000-000000000002"));

    @Test void singleAuthorizedTenantDoesNotRequireSelection() {
        assertEquals(A, selector.select(null, Map.of(A, Set.of("DOCUMENT_PROCESSOR"))));
    }

    @Test void multipleMembershipsRequireExplicitChoice() {
        var grants = Map.of(A, Set.of("DOCUMENT_PROCESSOR"), B, Set.of("TENANT_IT_ADMIN"));
        assertThrows(SecurityContextException.class, () -> selector.select(null, grants));
        assertEquals(B, selector.select(B.value().toString(), grants));
    }

    @Test void fabricatedOrMalformedTenantNeverGrantsAccess() {
        var onlyA = Map.of(A, Set.of("DOCUMENT_PROCESSOR"));
        assertThrows(SecurityContextException.class, () -> selector.select(B.value().toString(), onlyA));
        assertThrows(SecurityContextException.class, () -> selector.select("not-a-uuid", onlyA));
        assertThrows(SecurityContextException.class, () -> selector.select(null, Map.of()));
    }
}
