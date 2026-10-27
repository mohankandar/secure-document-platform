package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.ProductAuthorizationPolicy;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.support.model.SupportIncidentDetail;
import com.securedoc.platform.application.support.model.SupportIncidentSearchCriteria;
import com.securedoc.platform.application.support.model.SupportIncidentSearchPage;
import com.securedoc.platform.application.support.service.SupportIncidentQueryService;
import com.securedoc.platform.application.support.service.SupportIncidentActionService;
import com.securedoc.platform.application.exception.AccessDeniedException;
import com.securedoc.platform.domain.identity.TenantId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Incident search/triage API for Tenant IT and Platform Operations. Exceptional actions preserve
 * durable package/audit/support history; protected document/provider content is absent from this contract.
 */
@RestController
@RequestMapping("/api/v1/support/incidents")
public class SupportIncidentController {
    private final SupportIncidentQueryService queryService;
    private final SupportIncidentActionService actionService;
    private final RequestContextResolver contextResolver;
    private final ProductAuthorizationPolicy authorizationPolicy;

    public SupportIncidentController(SupportIncidentQueryService queryService,
                                     RequestContextResolver contextResolver,
                                     ProductAuthorizationPolicy authorizationPolicy) {
        this(queryService, null, contextResolver, authorizationPolicy);
    }

    @Autowired
    public SupportIncidentController(SupportIncidentQueryService queryService,
                                     SupportIncidentActionService actionService,
                                     RequestContextResolver contextResolver,
                                     ProductAuthorizationPolicy authorizationPolicy) {
        this.queryService = queryService;
        this.actionService = actionService;
        this.contextResolver = contextResolver;
        this.authorizationPolicy = authorizationPolicy;
    }

    @GetMapping
    @RequireCapability(UserCapability.TECHNICAL_EXCEPTION_VIEW)
    public ResponseEntity<SupportIncidentSearchPage> search(
            @RequestParam(required = false) String businessIdentifier,
            @RequestParam(required = false) UUID incidentId,
            @RequestParam(required = false) UUID correlationId,
            @RequestParam(required = false) UUID packageId,
            @RequestParam(required = false) UUID documentId,
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) String reasonCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String businessOutcome,
            @RequestParam(required = false) Boolean businessContinuityUsed,
            @RequestParam(required = false) Boolean retryable,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        TenantId effectiveTenant = resolveTenant(principal.tenantId(), tenantId, principal);
        return ResponseEntity.ok(queryService.search(new SupportIncidentSearchCriteria(
                effectiveTenant, businessIdentifier, incidentId, correlationId, packageId, documentId,
                stage, reasonCode, status, businessOutcome, businessContinuityUsed, retryable,
                from, to, page, size)));
    }

    @GetMapping("/{incidentId}")
    @RequireCapability(UserCapability.TECHNICAL_EXCEPTION_VIEW)
    public ResponseEntity<SupportIncidentDetail> detail(
            @PathVariable UUID incidentId,
            @RequestParam(required = false) UUID tenantId,
            HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        TenantId effectiveTenant = resolveTenant(principal.tenantId(), tenantId, principal);
        return ResponseEntity.ok(queryService.detail(incidentId, effectiveTenant));
    }

    @PostMapping("/{incidentId}/actions")
    @RequireCapability(UserCapability.TECHNICAL_EXCEPTION_VIEW)
    public ResponseEntity<SupportIncidentActionService.ActionResult> action(
            @PathVariable UUID incidentId,
            @RequestParam(required = false) UUID tenantId,
            @RequestBody IncidentActionRequest body,
            HttpServletRequest request) {
        if (body == null || body.action() == null || body.action().isBlank()) {
            throw new IllegalArgumentException("Support incident action is required");
        }
        var principal = contextResolver.resolvePrincipal(request);
        TenantId effectiveTenant = resolveTenantForMutation(principal.tenantId(), tenantId);
        UserCapability actionCapability = "REPROCESS".equalsIgnoreCase(body.action().trim())
                ? UserCapability.TECHNICAL_RETRY : UserCapability.TECHNICAL_HOLD_MANAGE;
        if (!authorizationPolicy.has(principal, actionCapability)) {
            throw new AccessDeniedException("Insufficient permissions for this support incident action");
        }
        if (actionService == null) {
            throw new IllegalStateException("Support incident actions are not configured");
        }
        return ResponseEntity.accepted().body(actionService.execute(incidentId, effectiveTenant,
                principal.actorId(), contextResolver.resolveCorrelationId(request),
                body.action(), body.holdDays(), body.resolutionCode()));
    }

    public record IncidentActionRequest(String action, Integer holdDays, String resolutionCode) {}

    private TenantId resolveTenantForMutation(TenantId principalTenant, UUID requestedTenant) {
        if (requestedTenant == null || requestedTenant.equals(principalTenant.value())) return principalTenant;
        throw new AccessDeniedException("Cross-tenant support incident actions are not permitted");
    }

    private TenantId resolveTenant(TenantId principalTenant,
                                   UUID requestedTenant,
                                   com.securedoc.platform.application.context.AuthenticatedPrincipal principal) {
        if (requestedTenant == null || requestedTenant.equals(principalTenant.value())) return principalTenant;
        if (!authorizationPolicy.has(principal, UserCapability.PLATFORM_OPERATIONS)) {
            throw new AccessDeniedException("Cross-tenant support search requires Platform Operations capability");
        }
        return TenantId.of(requestedTenant);
    }
}
