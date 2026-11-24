package com.securedoc.platform.api.notifications;

import com.documentplatform.notifications.approval.ApprovalAudit;
import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

/**
 * Task 0059: every use of an approval link in the entity's audit log, codes and ids only (no token, no name, no reason). A
 * decision is recorded in its own transaction's log, so it is undone with it; a refusal is recorded on its own and stays.
 */
@Component
public class ApprovalLinkAudit implements ApprovalAudit {

    private final AuditService audit;
    private final RequestContextResolver context;

    public ApprovalLinkAudit(AuditService audit, RequestContextResolver context) {
        this.audit = audit;
        this.context = context;
    }

    @Override
    public void record(Entry e) {
        TenantId entity = TenantId.of(UUID.fromString(e.tenant().value()));
        ActorId actor = ActorId.of(UUID.fromString(e.userId()));
        String action = e.action() == null ? "unknown" : e.action().name();
        String request = e.request() == null ? "unknown" : e.request().toString();
        if (e.outcome() == Outcome.DECIDED) {
            audit.recordSuccess(entity, actor, AuditAction.DELIVERY_APPROVAL_LINK_USED, "ApprovalRequest", request,
                    correlation(), "Approval link used: action=" + action);
        } else {
            String code = e.outcome() == Outcome.ALREADY_DECIDED ? "ALREADY_DECIDED" : e.refusal().name();
            refused(entity, actor, request, null, action, code);
        }
    }

    /** A refusal of LexaVault's own, or of opening a link. */
    public void refused(TenantId entity, ActorId actor, String request, PackageId caseId, String action, String code) {
        audit.recordDeniedIndependently(entity, actor, AuditAction.DELIVERY_APPROVAL_LINK_REFUSED, "ApprovalRequest", request,
                correlation(), "Approval link refused: action=" + action + ", code=" + code
                        + (caseId == null ? "" : ", package=" + caseId));
    }

    private CorrelationId correlation() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? context.resolveCorrelationId(attributes.getRequest()) : CorrelationId.generate();
    }
}
