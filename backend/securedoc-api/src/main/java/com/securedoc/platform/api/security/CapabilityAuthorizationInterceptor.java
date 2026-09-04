package com.securedoc.platform.api.security;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.AccessDeniedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** Enforces product capability requirements. Backend capability checks are the authorization boundary. */
@Component
public class CapabilityAuthorizationInterceptor implements HandlerInterceptor {

    private final RequestContextResolver contextResolver;
    private final ProductAuthorizationPolicy authorizationPolicy;

    public CapabilityAuthorizationInterceptor(RequestContextResolver contextResolver,
                                              ProductAuthorizationPolicy authorizationPolicy) {
        this.contextResolver = contextResolver;
        this.authorizationPolicy = authorizationPolicy;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) return true;

        RequireCapability annotation = handlerMethod.getMethodAnnotation(RequireCapability.class);
        if (annotation == null) return true;

        AuthenticatedPrincipal principal = contextResolver.resolvePrincipal(request);
        for (UserCapability capability : annotation.value()) {
            if (authorizationPolicy.has(principal, capability)) return true;
        }
        throw new AccessDeniedException("Insufficient permissions for this operation");
    }
}
