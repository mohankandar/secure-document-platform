package com.securedoc.platform.application.operations.service;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.operations.command.HidePackageFromDashboardCommand;
import com.securedoc.platform.application.operations.result.HidePackageFromDashboardResult;
import com.securedoc.platform.application.port.in.HidePackageFromDashboardUseCase;
import com.securedoc.platform.application.port.out.PackageDashboardVisibilityPort;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.PackageStatus;

import java.time.Instant;
import java.util.Objects;

/**
 * Removes a completed intake from normal Dashboard views only.
 * Package metadata, lifecycle history, audit history, and retention scheduling remain intact.
 */
public final class PackageDashboardVisibilityService implements HidePackageFromDashboardUseCase {

    private final PackageRepository packageRepository;
    private final PackageDashboardVisibilityPort dashboardVisibilityPort;
    private final AuditService auditService;

    public PackageDashboardVisibilityService(PackageRepository packageRepository,
                                             PackageDashboardVisibilityPort dashboardVisibilityPort,
                                             AuditService auditService) {
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.dashboardVisibilityPort = Objects.requireNonNull(dashboardVisibilityPort);
        this.auditService = Objects.requireNonNull(auditService);
    }

    @Override
    public HidePackageFromDashboardResult execute(HidePackageFromDashboardCommand command) {
        Objects.requireNonNull(command);
        DocumentPackage pkg = packageRepository.findByIdAndTenantId(command.packageId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("DocumentPackage", command.packageId().toString()));
        if (pkg.status() != PackageStatus.COMPLETED) {
            throw new ValidationException("Only a completed/exported package can be removed from the Dashboard");
        }

        Instant hiddenAt = Instant.now();
        dashboardVisibilityPort.hide(command.packageId(), command.tenantId(), command.actorId(), hiddenAt);
        auditService.recordSuccess(command.tenantId(), command.actorId(), AuditAction.PACKAGE_HIDDEN_FROM_DASHBOARD,
                "DocumentPackage", command.packageId().toString(), command.correlationId(),
                "Completed package removed from normal Dashboard views; retention lifecycle unchanged");
        return new HidePackageFromDashboardResult(command.packageId(), hiddenAt);
    }
}
