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
 * Every role must match an explicitly required role; no administrator bypass.
 */
@Component
public class RoleAuthorizationInterceptor implements HandlerInterceptor {

    private final RequestContextResolver contextResolver;

    public RoleAuthorizationInterceptor(RequestContextResolver contextResolver) {
        this.contextResolver = contextResolver;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        RequireRole annotation = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (annotation == null) {
            return true; // No role restriction
        }

        AuthenticatedPrincipal principal = contextResolver.resolvePrincipal(request);

        // Check if actor has at least one of the required roles
        for (String requiredRole : annotation.value()) {
            if (principal.hasRole(requiredRole.toUpperCase())) {
                return true;
            }
        }

        throw new AccessDeniedException("Insufficient permissions for this operation");
    }
}
