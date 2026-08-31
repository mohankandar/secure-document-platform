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
        tenantBoundaryGuard.requireProvisionedTenant(contextResolver.resolvePrincipal(request));
        return true;
    }
}
