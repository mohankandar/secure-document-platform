package com.securedoc.platform.api.security;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.AccessDeniedException;
import com.securedoc.platform.application.exception.TenantBoundaryDeniedException;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Task U022 step 0: records the 403s that {@link CapabilityAuthorizationInterceptor} does not, as a DENIED
 * {@code AUTHORIZATION_FAILED} record. The detail uses the 0018 format plus a reason:
 * {@code capability=none, method=GET, route=/api/v1/..., reason=TENANT_BOUNDARY|SERVICE}.
 *
 * <p>Never twice: whoever writes a DENIED record for a request (the capability interceptor, an endpoint with its own
 * refusal record, or this class) marks the request with {@link #RECORDED_ATTRIBUTE}, and a marked request is not
 * recorded again. A failure to identify the caller or to save the record never turns the 403 into something else.
 */
@Component
public class AccessDeniedAuditRecorder {

    public static final String RECORDED_ATTRIBUTE = AccessDeniedAuditRecorder.class.getName() + ".recorded";
    static final String REASON_TENANT_BOUNDARY = "TENANT_BOUNDARY";
    static final String REASON_SERVICE = "SERVICE";

    private static final Logger log = LoggerFactory.getLogger(AccessDeniedAuditRecorder.class);

    private final RequestContextResolver contextResolver;
    private final AuditService auditService;

    public AccessDeniedAuditRecorder(RequestContextResolver contextResolver, AuditService auditService) {
        this.contextResolver = contextResolver;
        this.auditService = auditService;
    }

    /** Marks the request as having its DENIED record already. */
    public static void markRecorded(HttpServletRequest request) {
        if (request != null) request.setAttribute(RECORDED_ATTRIBUTE, Boolean.TRUE);
    }

    public static boolean isRecorded(HttpServletRequest request) {
        return request != null && Boolean.TRUE.equals(request.getAttribute(RECORDED_ATTRIBUTE));
    }

    /** Records the refusal unless this request already has a DENIED record. */
    public void recordIfNotYet(HttpServletRequest request, AccessDeniedException ex) {
        if (request == null || isRecorded(request)) return;
        markRecorded(request);
        try {
            AuthenticatedPrincipal principal = MachineRoutes.machine(request)
                    ? contextResolver.resolveIntegrationPrincipal(request)
                    : contextResolver.resolvePrincipal(request);
            String route = CapabilityAuthorizationInterceptor.routePattern(request);
            auditService.recordDenied(principal.tenantId(), principal.actorId(), AuditAction.AUTHORIZATION_FAILED,
                    CapabilityAuthorizationInterceptor.RESOURCE_TYPE, route,
                    contextResolver.resolveCorrelationId(request), detail(request, ex));
        } catch (RuntimeException failure) {
            // No identity to record against, or the audit store refused: the 403 still stands.
            log.warn("AUTHORIZATION_FAILED_NOT_RECORDED reason={}", failure.getClass().getSimpleName());
        }
    }

    static String detail(HttpServletRequest request, AccessDeniedException ex) {
        String reason = ex instanceof TenantBoundaryDeniedException ? REASON_TENANT_BOUNDARY : REASON_SERVICE;
        return com.securedoc.platform.application.error.LvErrorCodes.auditDetail(com.securedoc.platform.application.error.LvErrorCodes.of(ex), ex.errorCode(), null)
                + ", capability=none, method=" + CapabilityAuthorizationInterceptor.method(request)
                + ", route=" + CapabilityAuthorizationInterceptor.routePattern(request) + ", reason=" + reason;
    }
}
