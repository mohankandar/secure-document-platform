package com.securedoc.platform.api.security;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.AccessDeniedException;
import com.securedoc.platform.application.port.out.TenantRepository;
import com.securedoc.platform.application.service.TenantContextService;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenantBoundaryGuardTest {
    @Test
    void unprovisionedTenantFailsClosedBeforeTenantOwnedWork() {
        TenantRepository repository = mock(TenantRepository.class);
        TenantId tenantId = TenantId.of(UUID.fromString("00000000-0000-0000-0000-000000000002"));
        when(repository.findById(tenantId)).thenReturn(Optional.empty());
        TenantBoundaryGuard guard = new TenantBoundaryGuard(new TenantContextService(repository));
        AuthenticatedPrincipal principal = AuthenticatedPrincipal.of(
                ActorId.of(UUID.randomUUID()), tenantId, "person@example.test", Set.of("USER"));
        assertThrows(AccessDeniedException.class, () -> guard.requireProvisionedTenant(principal));
    }
}
