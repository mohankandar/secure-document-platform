package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.ProductAuthorizationPolicy;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.support.model.SupportIncidentDetail;
import com.securedoc.platform.application.support.model.SupportIncidentSearchCriteria;
import com.securedoc.platform.application.support.model.SupportIncidentSearchPage;
import com.securedoc.platform.application.support.service.SupportIncidentQueryService;
import com.securedoc.platform.application.exception.AccessDeniedException;
import com.securedoc.platform.domain.identity.TenantId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only incident search/triage API for Tenant IT and Platform Operations.
 * Protected document/provider content is intentionally absent from this contract.
 */
@RestController
@RequestMapping("/api/v1/support/incidents")
public class SupportIncidentController {
    private final SupportIncidentQueryService queryService;
    private final RequestContextResolver contextResolver;
    private final ProductAuthorizationPolicy authorizationPolicy;

    public SupportIncidentController(SupportIncidentQueryService queryService,
                                     RequestContextResolver contextResolver,
                                     ProductAuthorizationPolicy authorizationPolicy) {
        this.queryService = queryService;
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
