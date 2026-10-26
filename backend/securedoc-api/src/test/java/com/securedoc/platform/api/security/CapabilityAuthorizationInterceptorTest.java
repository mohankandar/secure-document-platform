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

import java.util.Set;
import java.util.UUID;

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

    private void assertDenied(String role, String method) throws Exception {
        var fixture = fixture(role);
        assertThrows(AccessDeniedException.class, () -> fixture.interceptor.preHandle(
                fixture.request, mock(HttpServletResponse.class), handler(method)));
    }

    private void assertAllowed(String role, String method) throws Exception {
        var fixture = fixture(role);
        assertTrue(fixture.interceptor.preHandle(fixture.request, mock(HttpServletResponse.class), handler(method)));
    }

    private Fixture fixture(String role) {
        RequestContextResolver resolver = mock(RequestContextResolver.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        AuthenticatedPrincipal principal = AuthenticatedPrincipal.of(
                ActorId.of(UUID.randomUUID()), TenantId.of(UUID.randomUUID()), "test-user", Set.of(role));
        when(resolver.resolvePrincipal(request)).thenReturn(principal);
        return new Fixture(new CapabilityAuthorizationInterceptor(resolver, new ProductAuthorizationPolicy()), request);
    }

    private HandlerMethod handler(String method) throws NoSuchMethodException {
        return new HandlerMethod(new ProtectedActions(), ProtectedActions.class.getMethod(method));
    }

    private record Fixture(CapabilityAuthorizationInterceptor interceptor, HttpServletRequest request) {}

    public static class ProtectedActions {
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
    }
}
