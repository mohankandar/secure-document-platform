package com.securedoc.platform.api.security;

import com.securedoc.platform.api.context.RequestContextResolver;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Task 0075: the scanner routes ask for a machine, as the push routes do; a route that only looks like one for a person. */
class TenantBoundaryInterceptorTest {

    @Test
    void theMachineRoutesAskForAMachine() {
        for (String path : new String[] {"/api/v1/scanner-intakes", "/api/v1/scanner-intakes/x/documents/y",
                "/api/v1/external-intakes", "/api/v1/external-intakes/x"}) {
            RequestContextResolver resolver = ask(path);
            verify(resolver).resolveIntegrationPrincipal(any());
            verify(resolver, never()).resolvePrincipal(any());
            assertTrue(MachineRoutes.machine(request(path)), path);
        }
        for (String path : new String[] {"/api/v1/packages", "/api/v1/scanner-intakes-x", "/api/v1/external-intakesx"}) {
            RequestContextResolver resolver = ask(path);
            verify(resolver).resolvePrincipal(any());
            verify(resolver, never()).resolveIntegrationPrincipal(any());
        }
        assertFalse(MachineRoutes.scanner(request("/api/v1/external-intakes/x")));
    }

    private static RequestContextResolver ask(String path) {
        RequestContextResolver resolver = mock(RequestContextResolver.class);
        assertTrue(new TenantBoundaryInterceptor(resolver, mock(TenantBoundaryGuard.class))
                .preHandle(request(path), new MockHttpServletResponse(), new Object()));
        return resolver;
    }

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        return request;
    }
}
