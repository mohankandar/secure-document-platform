package com.securedoc.platform.api.security;

import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Task 0023 C5 (#239, #236): every sign-in admission refusal is logged with its reason code, and audited as a DENIED
 * {@code SECURITY_CONTEXT_INVALID} record when LexaVault already trusts which entity and which person it concerns.
 *
 * <p>Log line: {@code SECURITY_CONTEXT_REFUSED reason=<CODE> method=<M> route=<pattern> correlation=<id>
 * audited=<true|false>}. Audit detail: {@code method=<M>, route=<pattern>, reason=<CODE>}. Neither ever holds the
 * token, the email, the username, a group name or a header value: only the fixed reason code and the route pattern.
 * A refusal before any entity is established has no entity log to go to; it is logged only.
 *
 * <p>A failure to save the record never turns the 403 into something else.
 */
@Component
public class SecurityContextRefusalRecorder {

    static final String RESOURCE_TYPE = CapabilityAuthorizationInterceptor.RESOURCE_TYPE;
    private static final Logger log = LoggerFactory.getLogger(SecurityContextRefusalRecorder.class);

    private final AuditService auditService;

    public SecurityContextRefusalRecorder(AuditService auditService) {
        this.auditService = auditService;
    }

    /** A refused request (403 {@code SECURITY_CONTEXT_INVALID}). */
    public void recordRefusal(HttpServletRequest request, SecurityContextException refusal, String correlationId) {
        record(request, refusal.reasonCode(), refusal.tenantId(), refusal.actorId(), correlationId);
    }

    /**
     * An entity left out of the person's choices although they are admitted to it (#236: the entity is not ACTIVE, or
     * its row is missing). The request itself succeeds.
     */
    public void recordLeftOutEntity(HttpServletRequest request, String reasonCode, TenantId tenantId, ActorId actorId,
                                    String correlationId) {
        record(request, reasonCode, tenantId, actorId, correlationId);
    }

    private void record(HttpServletRequest request, String reasonCode, TenantId tenantId, ActorId actorId,
                        String correlationId) {
        String method = CapabilityAuthorizationInterceptor.method(request);
        String route = CapabilityAuthorizationInterceptor.routePattern(request);
        boolean audited = false;
        if (tenantId != null && actorId != null) {
            try {
                auditService.recordDenied(tenantId, actorId, AuditAction.SECURITY_CONTEXT_INVALID, RESOURCE_TYPE,
                        route, correlation(correlationId), detail(method, route, reasonCode));
                AccessDeniedAuditRecorder.markRecorded(request);
                audited = true;
            } catch (RuntimeException failure) {
                log.warn("SECURITY_CONTEXT_INVALID_NOT_RECORDED reason={} failure={}", reasonCode,
                        failure.getClass().getSimpleName());
            }
        }
        log.warn("SECURITY_CONTEXT_REFUSED reason={} method={} route={} correlation={} audited={}",
                reasonCode, method, route, correlationId, audited);
    }

    static String detail(String method, String route, String reasonCode) {
        return "method=" + method + ", route=" + route + ", reason=" + reasonCode;
    }

    private static CorrelationId correlation(String correlationId) {
        try {
            return CorrelationId.of(java.util.UUID.fromString(correlationId));
        } catch (RuntimeException invalid) {
            return CorrelationId.generate();
        }
    }
}
