package com.securedoc.platform.api.security;

import com.securedoc.platform.api.context.RequestContextResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TenantDiscoveryBoundaryTest {
    @Test void onlyExactGetTenantDiscoveryCanBypassSelectedTenantBoundary() {
        RequestContextResolver resolver = mock(RequestContextResolver.class);
        TenantBoundaryGuard guard = mock(TenantBoundaryGuard.class);
        TenantBoundaryInterceptor interceptor = new TenantBoundaryInterceptor(resolver, guard);
        HttpServletRequest discovery = mock(HttpServletRequest.class);
        when(discovery.getMethod()).thenReturn("GET");
        when(discovery.getServletPath()).thenReturn("/api/v1/me/tenants");
        assertTrue(interceptor.preHandle(discovery, mock(HttpServletResponse.class), new Object()));
        verifyNoInteractions(resolver, guard);

        HttpServletRequest otherMethod = mock(HttpServletRequest.class);
        when(otherMethod.getMethod()).thenReturn("POST");
        when(otherMethod.getServletPath()).thenReturn("/api/v1/me/tenants");
        interceptor.preHandle(otherMethod, mock(HttpServletResponse.class), new Object());
        verify(resolver).resolvePrincipal(otherMethod);
    }
}
