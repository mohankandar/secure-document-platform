package com.securedoc.platform.api.security;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.AccessDeniedException;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.TenantId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import com.securedoc.platform.application.compliance.AppendixDetailAllowList;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditOutcome;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.identity.CorrelationId;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapabilityAuthorizationInterceptorTest {

    @Test
    void documentProcessorDirectApiCannotReprocess() throws Exception {
        assertDenied("DOCUMENT_PROCESSOR", "reprocess");
    }

    @Test
    void reportViewerDirectApiCannotOpenDashboardOrExportCsv() throws Exception {
        assertDenied("REPORT_VIEWER", "dashboard");
        assertDenied("REPORT_VIEWER", "reportExport");
    }

    @Test
    void tenantItDirectApiCannotReviewOrApproveDelivery() throws Exception {
        assertDenied("TENANT_IT_ADMIN", "review");
        assertDenied("TENANT_IT_ADMIN", "approveDelivery");
    }

    @Test
    void businessAdminDirectApiCannotUseTechnicalRetry() throws Exception {
        assertDenied("BUSINESS_ADMIN", "technicalRetry");
    }

    @Test
    void onlySupervisorCanApproveDeliveryAndReprocess() throws Exception {
        assertAllowed("DOCUMENT_SUPERVISOR", "reprocess");
        assertAllowed("DOCUMENT_SUPERVISOR", "approveDelivery");
        assertDenied("DOCUMENT_PROCESSOR", "approveDelivery");
    }

    @Test
    void businessAdminDirectApiCannotProcessOrApproveDocuments() throws Exception {
        assertDenied("BUSINESS_ADMIN", "dashboard");
        assertDenied("BUSINESS_ADMIN", "review");
        assertDenied("BUSINESS_ADMIN", "approveDelivery");
        assertDenied("BUSINESS_ADMIN", "reprocess");
    }

    @Test
    void everyRefusalIsRecordedOnceAsDeniedWithCapabilityMethodAndRoutePatternOnly() throws Exception {
        var fixture = fixture("REPORT_VIEWER");
        when(fixture.request.getMethod()).thenReturn("POST");
        when(fixture.request.getRequestURI()).thenReturn("/api/v1/packages/9f1c-secret-loan-12345/reprocess");
        when(fixture.request.getQueryString()).thenReturn("borrower=Jane%20Doe");
        when(fixture.request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
                .thenReturn("/api/v1/packages/{packageId}/reprocess");

        assertThrows(AccessDeniedException.class, () -> fixture.interceptor.preHandle(
                fixture.request, mock(HttpServletResponse.class), handler("reprocess")));

        assertEquals(1, fixture.audits.size());
        AuditRecord record = fixture.audits.get(0);
        assertEquals(AuditAction.AUTHORIZATION_FAILED, record.action());
        assertEquals(AuditOutcome.DENIED, record.outcome());
        assertEquals(fixture.principal.actorId(), record.actorId());
        assertEquals(fixture.principal.tenantId(), record.tenantId());
        assertEquals("code=LV-AUT-006, detail=ACCESS_DENIED, capability=PACKAGE_REPROCESS, method=POST, route=/api/v1/packages/{packageId}/reprocess",
                record.detail());
        assertEquals("/api/v1/packages/{packageId}/reprocess", record.resourceId());
        for (String leaked : List.of("secret-loan", "12345", "Jane", "borrower")) {
            assertFalse(record.toString().contains(leaked), "audit record holds " + leaked);
        }
    }

    @Test
    void anAllowedRequestWritesNoRecord() throws Exception {
        var fixture = fixture("DOCUMENT_SUPERVISOR");
        assertTrue(fixture.interceptor.preHandle(fixture.request, mock(HttpServletResponse.class), handler("reprocess")));
        assertTrue(fixture.audits.isEmpty());
    }

    @Test
    void severalAcceptedCapabilitiesAreJoinedAndAMissingOrOddRouteIsUnknown() throws Exception {
        var fixture = fixture("REPORT_VIEWER");
        when(fixture.request.getMethod()).thenReturn("PUT");
        when(fixture.request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
                .thenReturn("/api/v1/x?name=Jane Doe");
        assertThrows(AccessDeniedException.class, () -> fixture.interceptor.preHandle(
                fixture.request, mock(HttpServletResponse.class), handler("policyOrIdentifiers")));
        assertEquals("code=LV-AUT-006, detail=ACCESS_DENIED, capability=TENANT_POLICY_MANAGE|TENANT_IDENTIFIER_MANAGE, method=PUT, route=unknown",
                fixture.audits.get(0).detail());
    }

    /** Hardening: endpoints deny by default. */
    @Test
    void anEndpointWithoutARuleIsRefusedEvenForTheWidestRoleAndRecordedOnce() throws Exception {
        var fixture = fixture("DOCUMENT_SUPERVISOR");
        when(fixture.request.getMethod()).thenReturn("GET");
        when(fixture.request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)).thenReturn("/api/v1/x");
        assertThrows(AccessDeniedException.class, () -> fixture.interceptor.preHandle(
                fixture.request, mock(HttpServletResponse.class), handler("withoutARule")));
        assertEquals(1, fixture.audits.size());
        assertEquals("code=LV-AUT-006, detail=NO_RULE, method=GET, route=/api/v1/x", fixture.audits.get(0).detail());
    }

    @Test
    void anEndpointMarkedOpenGoesOnWithNoRecord() throws Exception {
        var fixture = fixture("REPORT_VIEWER");
        assertTrue(fixture.interceptor.preHandle(fixture.request, mock(HttpServletResponse.class), handler("open")));
        assertTrue(fixture.audits.isEmpty());
    }

    @Test
    void authorizationFailedDetailIsPrintedInTheAppendix() {
        assertTrue(AppendixDetailAllowList.isAllowed(AuditAction.AUTHORIZATION_FAILED.name()));
        assertEquals("capability=REPORT_EXPORT, method=GET, route=/api/v1/operations/reports/export",
                AppendixDetailAllowList.printable("AUTHORIZATION_FAILED",
                        "capability=REPORT_EXPORT, method=GET, route=/api/v1/operations/reports/export"));
    }

    private void assertDenied(String role, String method) throws Exception {
        var fixture = fixture(role);
        assertThrows(AccessDeniedException.class, () -> fixture.interceptor.preHandle(
                fixture.request, mock(HttpServletResponse.class), handler(method)));
        assertEquals(1, fixture.audits.size(), "one DENIED record per refusal");
        assertEquals(AuditOutcome.DENIED, fixture.audits.get(0).outcome());
    }

    private void assertAllowed(String role, String method) throws Exception {
        var fixture = fixture(role);
        assertTrue(fixture.interceptor.preHandle(fixture.request, mock(HttpServletResponse.class), handler(method)));
        assertTrue(fixture.audits.isEmpty());
    }

    private Fixture fixture(String role) {
        RequestContextResolver resolver = mock(RequestContextResolver.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        AuthenticatedPrincipal principal = AuthenticatedPrincipal.of(
                ActorId.of(UUID.randomUUID()), TenantId.of(UUID.randomUUID()), "test-user", Set.of(role));
        when(resolver.resolvePrincipal(request)).thenReturn(principal);
        when(resolver.resolveCorrelationId(request)).thenReturn(CorrelationId.generate());
        List<AuditRecord> audits = new ArrayList<>();
        return new Fixture(new CapabilityAuthorizationInterceptor(resolver, new ProductAuthorizationPolicy(),
                new AuditService(audits::add)), request, principal, audits);
    }

    private HandlerMethod handler(String method) throws NoSuchMethodException {
        return new HandlerMethod(new ProtectedActions(), ProtectedActions.class.getMethod(method));
    }

    private record Fixture(CapabilityAuthorizationInterceptor interceptor, HttpServletRequest request,
                           AuthenticatedPrincipal principal, List<AuditRecord> audits) {}

    public static class ProtectedActions {
        @RequireCapability({UserCapability.TENANT_POLICY_MANAGE, UserCapability.TENANT_IDENTIFIER_MANAGE})
        public void policyOrIdentifiers() {}

        @RequireCapability(UserCapability.PACKAGE_REPROCESS)
        public void reprocess() {}

        @RequireCapability(UserCapability.DASHBOARD_ACCESS)
        public void dashboard() {}

        @RequireCapability(UserCapability.REPORT_EXPORT)
        public void reportExport() {}

        @RequireCapability(UserCapability.MANUAL_REVIEW)
        public void review() {}

        @RequireCapability(UserCapability.PACKAGE_APPROVE_DELIVERY)
        public void approveDelivery() {}

        @RequireCapability(UserCapability.TECHNICAL_RETRY)
        public void technicalRetry() {}

        public void withoutARule() {}

        @OpenEndpoint("test")
        public void open() {}
    }
}
