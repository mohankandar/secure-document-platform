package com.securedoc.platform.api.platform;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.dto.WatchedFolderScanResponse;
import com.securedoc.platform.api.security.RequireRole;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/platform-admin")
public class PlatformAdminController {
    private final PlatformHealthService healthService;
    private final WatchedFolderScheduler watchedFolderScheduler;
    private final RequestContextResolver contextResolver;
    private final AuditService auditService;

    public PlatformAdminController(PlatformHealthService healthService,
                                   WatchedFolderScheduler watchedFolderScheduler,
                                   RequestContextResolver contextResolver,
                                   AuditService auditService) {
        this.healthService = healthService;
        this.watchedFolderScheduler = watchedFolderScheduler;
        this.contextResolver = contextResolver;
        this.auditService = auditService;
    }

    @GetMapping("/overview")
    @RequireRole({"PLATFORM_ADMIN"})
    public ResponseEntity<PlatformAdminOverviewResponse> overview(HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        return ResponseEntity.ok(healthService.overview(principal.tenantId(), false));
    }

    @PostMapping("/health-check")
    @RequireRole({"PLATFORM_ADMIN"})
    public ResponseEntity<PlatformAdminOverviewResponse> healthCheck(HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        var correlationId = contextResolver.resolveCorrelationId(request);
        var response = healthService.overview(principal.tenantId(), true);
        auditService.recordSuccess(principal.tenantId(), principal.actorId(), AuditAction.PLATFORM_HEALTH_CHECKED,
                "PlatformAdmin", "health", correlationId, "Platform health check requested");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/watched-folder/scan")
    @RequireRole({"PLATFORM_ADMIN"})
    public ResponseEntity<WatchedFolderScanResponse> scanWatchedFolder(HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        return ResponseEntity.ok(watchedFolderScheduler.scanManual(principal, contextResolver.resolveCorrelationId(request)));
    }
}
