package com.securedoc.platform.api.security;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.AccessDeniedException;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Enforces product capability requirements. Backend capability checks are the authorization boundary.
 *
 * <p>Task 0018 A2: every refusal (403) is recorded as a DENIED {@code AUTHORIZATION_FAILED} audit record with the
 * detail {@code capability=<missing>, method=<HTTP method>, route=<route pattern>}. The route is the mapping pattern
 * from the code ({@code /api/v1/packages/{packageId}/approve}), never the actual URL, query string or body. A refused
 * handler never runs, so an endpoint that writes its own DENIED record cannot record the same request twice.
 */
@Component
public class CapabilityAuthorizationInterceptor implements HandlerInterceptor {

    static final String RESOURCE_TYPE = "ApiRoute";
    static final String UNKNOWN_ROUTE = "unknown";
    /** A route pattern as Spring maps it: path characters, braces for variables and wildcards only. */
    private static final Pattern SAFE_ROUTE = Pattern.compile("[A-Za-z0-9/_\\-{}.*:]{1,200}");

    private final RequestContextResolver contextResolver;
    private final ProductAuthorizationPolicy authorizationPolicy;
    private final AuditService auditService;

    public CapabilityAuthorizationInterceptor(RequestContextResolver contextResolver,
                                              ProductAuthorizationPolicy authorizationPolicy,
                                              AuditService auditService) {
        this.contextResolver = contextResolver;
        this.authorizationPolicy = authorizationPolicy;
        this.auditService = auditService;
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
        auditService.recordDenied(principal.tenantId(), principal.actorId(), AuditAction.AUTHORIZATION_FAILED,
                RESOURCE_TYPE, routePattern(request), contextResolver.resolveCorrelationId(request),
                detail(annotation.value(), request));
        AccessDeniedAuditRecorder.markRecorded(request);
        throw new AccessDeniedException("Insufficient permissions for this operation");
    }

    /** {@code capability=A, method=POST, route=/api/v1/...}; several accepted capabilities are joined by "|". */
    static String detail(UserCapability[] capabilities, HttpServletRequest request) {
        String capability = Arrays.stream(capabilities).map(Enum::name).collect(Collectors.joining("|"));
        return "capability=" + capability + ", method=" + method(request) + ", route=" + routePattern(request);
    }

    public static String method(HttpServletRequest request) {
        String method = request.getMethod();
        return method != null && method.matches("[A-Za-z]{1,10}") ? method.toUpperCase(Locale.ROOT) : "UNKNOWN";
    }

    /** The matched mapping pattern; {@link #UNKNOWN_ROUTE} when there is none or it does not look like one. */
    public static String routePattern(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = pattern == null ? null : pattern.toString();
        return route != null && SAFE_ROUTE.matcher(route).matches() ? route : UNKNOWN_ROUTE;
    }
}
