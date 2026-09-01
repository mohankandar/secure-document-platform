package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.operations.command.HidePackageFromDashboardCommand;
import com.securedoc.platform.application.port.in.HidePackageFromDashboardUseCase;
import com.securedoc.platform.domain.identity.PackageId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** Approver-only operational removal from normal Dashboard views; no package or artifact deletion occurs here. */
@RestController
@RequestMapping("/api/v1/packages")
public class PackageDashboardController {

    private final HidePackageFromDashboardUseCase hidePackageFromDashboardUseCase;
    private final RequestContextResolver contextResolver;

    public PackageDashboardController(HidePackageFromDashboardUseCase hidePackageFromDashboardUseCase,
                                      RequestContextResolver contextResolver) {
        this.hidePackageFromDashboardUseCase = hidePackageFromDashboardUseCase;
        this.contextResolver = contextResolver;
    }

    @PostMapping("/{packageId}/dashboard-hide")
    @RequireCapability(UserCapability.PACKAGE_REMOVE_FROM_DASHBOARD)
    public ResponseEntity<Map<String, Object>> hideFromDashboard(@PathVariable String packageId,
                                                                 HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        var result = hidePackageFromDashboardUseCase.execute(new HidePackageFromDashboardCommand(
                PackageId.of(UUID.fromString(packageId)), principal.tenantId(), principal.actorId(),
                contextResolver.resolveCorrelationId(request)));
        return ResponseEntity.ok(Map.of(
                "packageId", result.packageId().toString(),
                "hiddenFromDashboard", true,
                "hiddenAt", result.hiddenAt().toString()));
    }
}
