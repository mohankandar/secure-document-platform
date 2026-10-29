package com.securedoc.platform.api.security;

import com.securedoc.platform.api.context.RequestContextResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class TenantBoundaryInterceptor implements HandlerInterceptor {
    private final RequestContextResolver contextResolver;
    private final TenantBoundaryGuard tenantBoundaryGuard;

    public TenantBoundaryInterceptor(RequestContextResolver contextResolver, TenantBoundaryGuard tenantBoundaryGuard) {
        this.contextResolver = contextResolver;
        this.tenantBoundaryGuard = tenantBoundaryGuard;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Tenant discovery is the sole pre-selection route. It still requires a valid OIDC JWT
        // and explicitly enabled trusted membership resolution in its controller.
        if ("GET".equals(request.getMethod()) && "/api/v1/me/tenants".equals(request.getServletPath())) {
            return true;
        }
        if (request.getServletPath().equals("/api/v1/external-intakes")
                || request.getServletPath().startsWith("/api/v1/external-intakes/")) {
            tenantBoundaryGuard.requireProvisionedTenant(contextResolver.resolveIntegrationPrincipal(request));
            return true;
        }
        tenantBoundaryGuard.requireProvisionedTenant(contextResolver.resolvePrincipal(request));
        return true;
    }
}
