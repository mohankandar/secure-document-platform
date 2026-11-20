package com.securedoc.platform.api.security;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.api.exception.GlobalExceptionHandler;
import com.securedoc.platform.application.compliance.AppendixDetailAllowList;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.AccessDeniedException;
import com.securedoc.platform.application.exception.TenantBoundaryDeniedException;
import com.securedoc.platform.application.port.out.TenantRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.application.service.TenantContextService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditOutcome;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** U022 step 0: tenant-boundary and service 403s are recorded once as DENIED AUTHORIZATION_FAILED. */
class AccessDeniedAuditRecorderTest {

    private final List<AuditRecord> audits = new ArrayList<>();
    private final AuditService auditService = new AuditService(audits::add);
    private final RequestContextResolver resolver = mock(RequestContextResolver.class);
    private final AuthenticatedPrincipal principal = AuthenticatedPrincipal.of(
            ActorId.of(UUID.randomUUID()), TenantId.of(UUID.randomUUID()), "person@example.test",
            Set.of("REPORT_VIEWER"));
    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler(new AccessDeniedAuditRecorder(resolver, auditService));

    AccessDeniedAuditRecorderTest() {
        when(resolver.resolvePrincipal(any())).thenReturn(principal);
        when(resolver.resolveCorrelationId(any())).thenReturn(CorrelationId.generate());
    }

    private static MockHttpServletRequest request(String method, String url, String pattern) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, url);
        request.setServletPath(url);
        request.setQueryString("borrower=Jane%20Doe");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        return request;
    }

    @Test
    void theTenantBoundaryGuardRefusalIsRecordedWithReasonTenantBoundary() {
        TenantRepository tenants = mock(TenantRepository.class);
        when(tenants.findById(principal.tenantId())).thenReturn(Optional.empty());
        TenantBoundaryGuard guard = new TenantBoundaryGuard(new TenantContextService(tenants));
        AccessDeniedException refused = assertThrows(TenantBoundaryDeniedException.class,
                () -> guard.requireProvisionedTenant(principal));
        var request = request("GET", "/api/v1/packages/LOAN-55120", "/api/v1/packages/{packageId}");

        assertEquals(HttpStatus.FORBIDDEN, handler.handleAccessDenied(refused, request).getStatusCode());

        assertEquals(1, audits.size());
        AuditRecord record = audits.get(0);
        assertEquals(AuditAction.AUTHORIZATION_FAILED, record.action());
        assertEquals(AuditOutcome.DENIED, record.outcome());
        assertEquals(principal.tenantId(), record.tenantId());
        assertEquals(principal.actorId(), record.actorId());
        assertEquals("ApiRoute", record.resourceType());
        assertEquals("/api/v1/packages/{packageId}", record.resourceId());
        assertEquals("capability=none, method=GET, route=/api/v1/packages/{packageId}, reason=TENANT_BOUNDARY",
                record.detail());
        for (String leaked : List.of("LOAN-55120", "Jane", "borrower")) {
            assertFalse(record.toString().contains(leaked), "audit record holds " + leaked);
        }
    }

    @Test
    void aRequestNamingAnotherEntityIsTenantBoundary() {
        var request = request("POST", "/api/v1/support/incidents/abc/actions", "/api/v1/support/incidents/{incidentId}/actions");
        handler.handleAccessDenied(
                new TenantBoundaryDeniedException("Cross-tenant support incident actions are not permitted"), request);
        assertEquals("capability=none, method=POST, route=/api/v1/support/incidents/{incidentId}/actions, "
                + "reason=TENANT_BOUNDARY", audits.get(0).detail());
    }

    @Test
    void anAccessDeniedThrownInsideAServiceIsRecordedWithReasonService() {
        var request = request("POST", "/api/v1/integrations/7/poll", "/api/v1/integrations/{connectionId}/poll");
        handler.handleAccessDenied(
                new AccessDeniedException("Integration connection is not an enabled polling source"), request);
        assertEquals(1, audits.size());
        assertEquals("capability=none, method=POST, route=/api/v1/integrations/{connectionId}/poll, reason=SERVICE",
                audits.get(0).detail());
        assertTrue(AppendixDetailAllowList.isAllowed(audits.get(0).action().name()));
    }

    @Test
    void aCapabilityRefusalIsRecordedByTheInterceptorOnlyNeverTwice() throws Exception {
        var request = request("POST", "/api/v1/packages/x/reprocess", "/api/v1/packages/{packageId}/reprocess");
        var interceptor = new CapabilityAuthorizationInterceptor(resolver, new ProductAuthorizationPolicy(), auditService);
        HandlerMethod method = new HandlerMethod(new CapabilityAuthorizationInterceptorTest.ProtectedActions(),
                CapabilityAuthorizationInterceptorTest.ProtectedActions.class.getMethod("reprocess"));

        AccessDeniedException refused = assertThrows(AccessDeniedException.class,
                () -> interceptor.preHandle(request, mock(HttpServletResponse.class), method));
        handler.handleAccessDenied(refused, request);

        assertEquals(1, audits.size(), "one record per request");
        assertEquals("capability=PACKAGE_REPROCESS, method=POST, route=/api/v1/packages/{packageId}/reprocess",
                audits.get(0).detail());
    }

    @Test
    void theSameRequestHandledTwiceIsRecordedOnce() {
        var request = request("GET", "/api/v1/artifacts/1/download", "/api/v1/artifacts/{artifactId}/download");
        handler.handleAccessDenied(new AccessDeniedException("Document downloads are not permitted"), request);
        handler.handleAccessDenied(new AccessDeniedException("Document downloads are not permitted"), request);
        assertEquals(1, audits.size());
    }

    @Test
    void anIntegrationRouteIsRecordedAgainstTheMachineClient() {
        AuthenticatedPrincipal machine = AuthenticatedPrincipal.of(
                ActorId.of(UUID.randomUUID()), principal.tenantId(), "los-client", Set.of());
        when(resolver.resolveIntegrationPrincipal(any())).thenReturn(machine);
        var request = request("POST", "/api/v1/external-intakes", "/api/v1/external-intakes");
        handler.handleAccessDenied(new AccessDeniedException("Polling source is not enabled for this tenant"), request);
        assertEquals(machine.actorId(), audits.get(0).actorId());
    }

    @Test
    void withNoIdentityOrABrokenAuditStoreTheRefusalStillReturns403AndNothingIsInvented() {
        RequestContextResolver noIdentity = mock(RequestContextResolver.class);
        when(noIdentity.resolvePrincipal(any())).thenThrow(new SecurityContextException("no identity"));
        var noIdentityHandler = new GlobalExceptionHandler(new AccessDeniedAuditRecorder(noIdentity, auditService));
        var response = noIdentityHandler.handleAccessDenied(new AccessDeniedException("x"),
                request("GET", "/api/v1/me", "/api/v1/me"));
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertTrue(audits.isEmpty());

        AuditService broken = new AuditService(r -> { throw new IllegalStateException("store down"); });
        var brokenHandler = new GlobalExceptionHandler(new AccessDeniedAuditRecorder(resolver, broken));
        assertEquals(HttpStatus.FORBIDDEN, brokenHandler.handleAccessDenied(new AccessDeniedException("x"),
                request("GET", "/api/v1/me", "/api/v1/me")).getStatusCode());
    }
}
