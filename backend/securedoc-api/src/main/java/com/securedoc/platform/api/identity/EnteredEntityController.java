package com.securedoc.platform.api.identity;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.identity.LastEntityStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Task 0058: the person enters an entity (they chose it, or switched to it). There is no body: the entity is the one of this
 * request, which the sign-in check and the entity check have just allowed. It is remembered for the next sign-in, and a
 * change is recorded in the log of the entity entered and of the one left. Remembering it gives nobody access.
 */
@RestController
@RequestMapping("/api/v1/me/entity")
public class EnteredEntityController {

    private final RequestContextResolver context;
    private final LastEntityStore lastEntity;
    private final AuditService audit;

    public EnteredEntityController(RequestContextResolver context, LastEntityStore lastEntity, AuditService audit) {
        this.context = context;
        this.lastEntity = lastEntity;
        this.audit = audit;
    }

    @PostMapping
    @Transactional
    public ResponseEntity<Void> entered(HttpServletRequest request) {
        var principal = context.resolvePrincipal(request);
        CorrelationId correlation = context.resolveCorrelationId(request);
        LastEntityStore.Change change = lastEntity.save(principal.actorId(), principal.tenantId(), Instant.now());
        if (change.changed()) {
            record(principal.tenantId(), principal, correlation, change.before().isPresent() ? "direction=ENTERED"
                    : "direction=ENTERED, first=true");
            change.before().ifPresent(left -> record(left, principal, correlation, "direction=LEFT"));
        }
        return ResponseEntity.noContent().build();
    }

    private void record(TenantId log, com.securedoc.platform.application.context.AuthenticatedPrincipal principal,
                        CorrelationId correlation, String detail) {
        // Neither entity's log names the other one.
        audit.recordSuccess(log, principal.actorId(), AuditAction.ENTITY_SWITCHED, "Entity", log.toString(), correlation, detail);
    }
}
