package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.api.security.SecurityMode;
import com.securedoc.platform.application.port.out.TenantRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class OidcTenantDiscoveryControllerTest {
    @Test
    void localSecurityModeCannotEnumerateTenantMemberships() {
        RequestContextResolver resolver = mock(RequestContextResolver.class);
        TenantRepository repository = mock(TenantRepository.class);
        when(resolver.securityMode()).thenReturn(SecurityMode.LOCAL);

        OidcTenantDiscoveryController controller = new OidcTenantDiscoveryController(resolver, repository);
        assertThrows(SecurityContextException.class, controller::list);
        verify(resolver, never()).resolveAvailableOidcTenants();
        verifyNoInteractions(repository);
    }
}
