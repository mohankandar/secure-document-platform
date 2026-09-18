package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.api.storage.ProcessingStorageNodeMonitor;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.application.storage.ProcessingStorageConfigurationService;
import com.securedoc.platform.application.storage.ProcessingStorageOverview;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.storage.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Platform Operations API for internal processing storage. Final export/delivery is intentionally out of scope. */
@RestController
@RequestMapping("/api/v1/platform/processing-storage")
public class ProcessingStorageController {
    private final ProcessingStorageConfigurationService service;
    private final ProcessingStorageNodeMonitor nodeMonitor;
    private final RequestContextResolver contextResolver;
    private final AuditService auditService;

    public ProcessingStorageController(ProcessingStorageConfigurationService service,
                                       ProcessingStorageNodeMonitor nodeMonitor,
                                       RequestContextResolver contextResolver,
                                       AuditService auditService) {
        this.service = service;
        this.nodeMonitor = nodeMonitor;
        this.contextResolver = contextResolver;
        this.auditService = auditService;
    }

    @GetMapping
    @RequireCapability(UserCapability.PLATFORM_OPERATIONS)
    public ResponseEntity<OverviewResponse> overview() {
        return ResponseEntity.ok(OverviewResponse.from(service.overview()));
    }

    @PostMapping("/providers")
    @Transactional
    @RequireCapability(UserCapability.PLATFORM_OPERATIONS)
    public ResponseEntity<ProviderResponse> createProvider(@RequestBody CreateProviderRequest body,
                                                            HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        ProcessingStorageProvider provider = service.createProvider(
                body.name(), ProcessingStorageProviderType.valueOf(body.providerType().trim().toUpperCase()),
                body.rootPath(), principal.actorId().value());
        auditService.recordSuccess(principal.tenantId(), principal.actorId(), AuditAction.PROCESSING_STORAGE_PROVIDER_CREATED,
                "ProcessingStorageProvider", provider.id().toString(), contextResolver.resolveCorrelationId(request),
                "Processing storage provider created: type=" + provider.providerType());
        return ResponseEntity.ok(ProviderResponse.from(provider));
    }

    @PostMapping("/providers/{providerId}/test")
    @RequireCapability(UserCapability.PLATFORM_OPERATIONS)
    public ResponseEntity<OverviewResponse> testProvider(@PathVariable UUID providerId, HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        service.getProvider(providerId);
        // The current node validates immediately; every other active node validates on its normal monitor interval.
        nodeMonitor.heartbeatAndValidate();
        auditService.recordSuccess(principal.tenantId(), principal.actorId(), AuditAction.PROCESSING_STORAGE_PROVIDER_TESTED,
                "ProcessingStorageProvider", providerId.toString(), contextResolver.resolveCorrelationId(request),
                "Processing storage validation requested across active nodes");
        return ResponseEntity.ok(OverviewResponse.from(service.overview()));
    }

    @PostMapping("/providers/{providerId}/activate-default")
    @Transactional
    @RequireCapability(UserCapability.PLATFORM_OPERATIONS)
    public ResponseEntity<ProviderResponse> activateDefault(@PathVariable UUID providerId, HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        ProcessingStorageProvider provider = service.activateDeploymentDefault(providerId, principal.actorId().value());
        auditService.recordSuccess(principal.tenantId(), principal.actorId(), AuditAction.PROCESSING_STORAGE_PROVIDER_ACTIVATED,
                "ProcessingStorageProvider", provider.id().toString(), contextResolver.resolveCorrelationId(request),
                "Processing storage activated as deployment default");
        return ResponseEntity.ok(ProviderResponse.from(provider));
    }

    @PostMapping("/providers/{providerId}/activate-tenant/{tenantId}")
    @Transactional
    @RequireCapability(UserCapability.PLATFORM_OPERATIONS)
    public ResponseEntity<ProviderResponse> activateTenant(@PathVariable UUID providerId,
                                                            @PathVariable UUID tenantId,
                                                            HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        ProcessingStorageProvider provider = service.activateTenantOverride(TenantId.of(tenantId), providerId, principal.actorId().value());
        auditService.recordSuccess(principal.tenantId(), principal.actorId(), AuditAction.PROCESSING_STORAGE_TENANT_OVERRIDE_CHANGED,
                "ProcessingStorageProvider", provider.id().toString(), contextResolver.resolveCorrelationId(request),
                "Tenant processing storage override activated: tenantId=" + tenantId);
        return ResponseEntity.ok(ProviderResponse.from(provider));
    }

    @DeleteMapping("/tenant-overrides/{tenantId}")
    @Transactional
    @RequireCapability(UserCapability.PLATFORM_OPERATIONS)
    public ResponseEntity<Void> clearTenantOverride(@PathVariable UUID tenantId, HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        service.clearTenantOverride(TenantId.of(tenantId));
        auditService.recordSuccess(principal.tenantId(), principal.actorId(), AuditAction.PROCESSING_STORAGE_TENANT_OVERRIDE_CHANGED,
                "Tenant", tenantId.toString(), contextResolver.resolveCorrelationId(request),
                "Tenant processing storage override cleared; deployment default will be used");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/providers/{providerId}/retire")
    @Transactional
    @RequireCapability(UserCapability.PLATFORM_OPERATIONS)
    public ResponseEntity<ProviderResponse> retire(@PathVariable UUID providerId, HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        ProcessingStorageProvider provider = service.retire(providerId);
        auditService.recordSuccess(principal.tenantId(), principal.actorId(), AuditAction.PROCESSING_STORAGE_PROVIDER_RETIRED,
                "ProcessingStorageProvider", provider.id().toString(), contextResolver.resolveCorrelationId(request),
                "Processing storage provider retired");
        return ResponseEntity.ok(ProviderResponse.from(provider));
    }

    public record CreateProviderRequest(String name, String providerType, String rootPath) {}

    public record ProviderResponse(UUID id, String name, String providerType, String rootPath,
                                   String status, int configurationVersion) {
        static ProviderResponse from(ProcessingStorageProvider p) {
            return new ProviderResponse(p.id(), p.name(), p.providerType().name(), p.rootPath(),
                    p.status().name(), p.configurationVersion());
        }
    }

    public record AssignmentResponse(UUID id, UUID tenantId, UUID providerId, String scope, String activatedAt) {
        static AssignmentResponse from(ProcessingStorageAssignment a) {
            return new AssignmentResponse(a.id(), a.tenantId() == null ? null : a.tenantId().value(), a.providerId(),
                    a.deploymentDefault() ? "DEPLOYMENT_DEFAULT" : "TENANT_OVERRIDE", a.activatedAt().toString());
        }
    }

    public record NodeValidationResponse(String nodeId, UUID processInstanceId, UUID providerId,
                                         String status, String failureCode, String checkedAt) {
        static NodeValidationResponse from(ProcessingStorageNodeValidation v) {
            return new NodeValidationResponse(v.nodeId(), v.processInstanceId(), v.providerId(),
                    v.status().name(), v.failureCode(), v.checkedAt().toString());
        }
    }

    public record TenantResponse(UUID tenantId, String name, String slug, String status) {
        static TenantResponse from(com.securedoc.platform.application.storage.ProcessingStorageTenantSummary tenant) {
            return new TenantResponse(tenant.tenantId(), tenant.name(), tenant.slug(), tenant.status());
        }
    }

    public record OverviewResponse(boolean ready, AssignmentResponse deploymentDefault,
                                   List<AssignmentResponse> tenantOverrides,
                                   List<ProviderResponse> providers,
                                   List<NodeValidationResponse> nodeValidations,
                                   List<TenantResponse> tenants,
                                   long activeNodeCount) {
        static OverviewResponse from(ProcessingStorageOverview o) {
            return new OverviewResponse(o.ready(),
                    o.deploymentDefault() == null ? null : AssignmentResponse.from(o.deploymentDefault()),
                    o.tenantOverrides().stream().map(AssignmentResponse::from).toList(),
                    o.providers().stream().map(ProviderResponse::from).toList(),
                    o.nodeValidations().stream().map(NodeValidationResponse::from).toList(),
                    o.tenants().stream().map(TenantResponse::from).toList(),
                    o.activeNodeCount());
        }
    }
}
