package com.securedoc.platform.api.platform;

import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.dto.WatchedFolderScanResponse;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.intake.watched.WatchedFolderSettingsService;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.SensitivityLevel;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/platform-admin")
public class PlatformAdminController {
    private final PlatformHealthService healthService;
    private final WatchedFolderScheduler watchedFolderScheduler;
    private final RequestContextResolver contextResolver;
    private final AuditService auditService;
    private final WatchedFolderSettingsService watchedFolderSettings;

    public PlatformAdminController(PlatformHealthService healthService,
                                   WatchedFolderScheduler watchedFolderScheduler,
                                   RequestContextResolver contextResolver,
                                   AuditService auditService,
                                   WatchedFolderSettingsService watchedFolderSettings) {
        this.healthService = healthService;
        this.watchedFolderScheduler = watchedFolderScheduler;
        this.contextResolver = contextResolver;
        this.auditService = auditService;
        this.watchedFolderSettings = watchedFolderSettings;
    }

    @GetMapping("/overview")
    @RequireCapability(UserCapability.TECHNICAL_ADMIN_VIEW)
    public ResponseEntity<PlatformAdminOverviewResponse> overview(HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        return ResponseEntity.ok(healthService.overview(principal.tenantId(), false));
    }

    @PostMapping("/health-check")
    @RequireCapability(UserCapability.PLATFORM_OPERATIONS)
    public ResponseEntity<PlatformAdminOverviewResponse> healthCheck(HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        var correlationId = contextResolver.resolveCorrelationId(request);
        var response = healthService.overview(principal.tenantId(), true);
        auditService.recordSuccess(principal.tenantId(), principal.actorId(), AuditAction.PLATFORM_HEALTH_CHECKED,
                "PlatformAdmin", "health", correlationId, "Platform health check requested");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/watched-folder/scan")
    @RequireCapability(UserCapability.TENANT_INTEGRATION_MANAGE)
    public ResponseEntity<WatchedFolderScanResponse> scanWatchedFolder(HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        return ResponseEntity.ok(watchedFolderScheduler.scanManual(principal, contextResolver.resolveCorrelationId(request)));
    }

    /** Task 0021: the level the installed watched folder gives every file it picks up (default Standard). */
    @GetMapping("/watched-folder/settings")
    @RequireCapability(UserCapability.TECHNICAL_ADMIN_VIEW)
    @Transactional(readOnly = true)
    public ResponseEntity<WatchedFolderSettingsResponse> watchedFolderSettings(HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        String folder = WatchedFolderSettingsService.INSTALLED_FOLDER;
        return ResponseEntity.ok(new WatchedFolderSettingsResponse(folder,
                watchedFolderSettings.sensitivityLevel(principal.tenantId(), folder).name()));
    }

    /** Task 0021: Tenant IT sets the folder's level. An admin setting, so only an exact level name is accepted. */
    @PutMapping("/watched-folder/settings")
    @RequireCapability(UserCapability.TENANT_INTEGRATION_MANAGE)
    @Transactional
    public ResponseEntity<WatchedFolderSettingsResponse> changeWatchedFolderSettings(
            @RequestBody WatchedFolderSettingsRequest body, HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        SensitivityLevel.Received level = SensitivityLevel.fromSource(body == null ? null : body.sensitivityLevel());
        if (body == null || body.sensitivityLevel() == null || level.unknown()) {
            throw new ValidationException(LvErrorCode.CFG_001, "sensitivityLevel must be STANDARD, CONFIDENTIAL or HIGHLY_CONFIDENTIAL");
        }
        String folder = WatchedFolderSettingsService.INSTALLED_FOLDER;
        SensitivityLevel saved = watchedFolderSettings.changeSensitivityLevel(principal.tenantId(), principal.actorId(),
                folder, level.level(), contextResolver.resolveCorrelationId(request));
        return ResponseEntity.ok(new WatchedFolderSettingsResponse(folder, saved.name()));
    }
}
