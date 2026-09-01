package com.securedoc.platform.api.security;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.AccessDeniedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Interceptor that enforces role-based access control on endpoints annotated with @RequireRole.
 * ADMIN role always passes. Other roles must match at least one required role.
 */
@Component
public class RoleAuthorizationInterceptor implements HandlerInterceptor {

    private final RequestContextResolver contextResolver;
    private final ProductAuthorizationPolicy authorizationPolicy;

    public RoleAuthorizationInterceptor(RequestContextResolver contextResolver,
                                        ProductAuthorizationPolicy authorizationPolicy) {
        this.contextResolver = contextResolver;
        this.authorizationPolicy = authorizationPolicy;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        RequireCapability capabilityAnnotation = handlerMethod.getMethodAnnotation(RequireCapability.class);
        RequireRole roleAnnotation = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (capabilityAnnotation == null && roleAnnotation == null) {
            return true;
        }

        AuthenticatedPrincipal principal = contextResolver.resolvePrincipal(request);
        if (principal.hasRole("ADMIN")) return true;

        if (capabilityAnnotation != null) {
            for (UserCapability capability : capabilityAnnotation.value()) {
                if (authorizationPolicy.has(principal, capability)) return true;
            }
            throw new AccessDeniedException("Insufficient permissions for this operation");
        }

        for (String requiredRole : roleAnnotation.value()) {
            if (principal.hasRole(requiredRole.toUpperCase())) return true;
        }
        throw new AccessDeniedException("Insufficient permissions for this operation");
    }
}
