package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.dto.PackageLifecycleResponse;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.lifecycle.command.ApproveFinalDeliveryCommand;
import com.securedoc.platform.application.lifecycle.command.ExtendTechnicalHoldCommand;
import com.securedoc.platform.application.lifecycle.command.PurgeDueRetentionCommand;
import com.securedoc.platform.application.lifecycle.command.ReprocessPackageCommand;
import com.securedoc.platform.application.port.in.ApproveFinalDeliveryUseCase;
import com.securedoc.platform.application.port.in.ExtendTechnicalHoldUseCase;
import com.securedoc.platform.application.port.in.GetPackageLifecycleQuery;
import com.securedoc.platform.application.port.in.PurgeDueRetentionUseCase;
import com.securedoc.platform.application.port.in.ReprocessPackageUseCase;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class PackageLifecycleController {
    private final GetPackageLifecycleQuery lifecycleQuery;
    private final ReprocessPackageUseCase reprocessPackageUseCase;
    private final ApproveFinalDeliveryUseCase approveFinalDeliveryUseCase;
    private final ExtendTechnicalHoldUseCase extendTechnicalHoldUseCase;
    private final PurgeDueRetentionUseCase purgeDueRetentionUseCase;
    private final RequestContextResolver contextResolver;

    public PackageLifecycleController(GetPackageLifecycleQuery lifecycleQuery,
                                      ReprocessPackageUseCase reprocessPackageUseCase,
                                      ApproveFinalDeliveryUseCase approveFinalDeliveryUseCase,
                                      ExtendTechnicalHoldUseCase extendTechnicalHoldUseCase,
                                      PurgeDueRetentionUseCase purgeDueRetentionUseCase,
                                      RequestContextResolver contextResolver) {
        this.lifecycleQuery = lifecycleQuery;
        this.reprocessPackageUseCase = reprocessPackageUseCase;
        this.approveFinalDeliveryUseCase = approveFinalDeliveryUseCase;
        this.extendTechnicalHoldUseCase = extendTechnicalHoldUseCase;
        this.purgeDueRetentionUseCase = purgeDueRetentionUseCase;
        this.contextResolver = contextResolver;
    }

    @GetMapping("/packages/{packageId}/lifecycle")
    @RequireCapability(UserCapability.DASHBOARD_ACCESS)
    public ResponseEntity<PackageLifecycleResponse> getLifecycle(@PathVariable String packageId,
                                                                 HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        return ResponseEntity.ok(PackageLifecycleResponse.from(lifecycleQuery.execute(packageId(packageId), principal.tenantId())));
    }

    @PostMapping("/packages/{packageId}/reprocess")
    @RequireCapability(UserCapability.PACKAGE_REPROCESS)
    public ResponseEntity<Map<String, Object>> reprocess(@PathVariable String packageId,
                                                          HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        var result = reprocessPackageUseCase.execute(new ReprocessPackageCommand(
                packageId(packageId), principal.tenantId(), principal.actorId(),
                contextResolver.resolveCorrelationId(request)));
        return ResponseEntity.ok(Map.of(
                "packageId", result.packageId().toString(),
                "status", result.status().name(),
                "nextExportCycle", result.nextExportCycle()));
    }

    @PostMapping("/packages/{packageId}/approve-delivery")
    @RequireCapability(UserCapability.PACKAGE_APPROVE_DELIVERY)
    public ResponseEntity<Map<String, String>> approveDelivery(@PathVariable String packageId,
                                                                HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        approveFinalDeliveryUseCase.execute(new ApproveFinalDeliveryCommand(
                packageId(packageId), principal.tenantId(), principal.actorId(),
                contextResolver.resolveCorrelationId(request)));
        return ResponseEntity.ok(Map.of("packageId", packageId, "status", "APPROVED_FOR_DELIVERY"));
    }

    @PostMapping("/packages/{packageId}/technical-hold")
    @RequireCapability(UserCapability.TECHNICAL_HOLD_MANAGE)
    public ResponseEntity<PackageLifecycleResponse> extendTechnicalHold(
            @PathVariable String packageId,
            @RequestBody Map<String, Integer> body,
            HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        int hours = body == null ? 0 : body.getOrDefault("holdHoursFromNow", 0);
        return ResponseEntity.ok(PackageLifecycleResponse.from(
                extendTechnicalHoldUseCase.execute(new ExtendTechnicalHoldCommand(
                        packageId(packageId), principal.tenantId(), principal.actorId(),
                        contextResolver.resolveCorrelationId(request), hours))));
    }

    @PostMapping("/lifecycle/purge-due")
    @RequireCapability(UserCapability.PLATFORM_OPERATIONS)
    public ResponseEntity<Map<String, Integer>> purgeDue(@RequestParam(defaultValue = "100") int limit,
                                                          HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        var result = purgeDueRetentionUseCase.execute(new PurgeDueRetentionCommand(
                Instant.now(), limit, principal.actorId(), contextResolver.resolveCorrelationId(request)));
        return ResponseEntity.ok(Map.of(
                "redactedCachesPurged", result.redactedCachesPurged(),
                "packagesFullyPurged", result.packagesFullyPurged()));
    }

    private PackageId packageId(String raw) {
        return PackageId.of(UUID.fromString(raw));
    }
}
