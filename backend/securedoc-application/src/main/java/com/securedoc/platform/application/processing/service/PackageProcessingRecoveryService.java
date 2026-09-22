package com.securedoc.platform.application.processing.service;

import com.securedoc.platform.application.lifecycle.service.PackageLifecycleService;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.application.technical.service.ProcessingIncidentService;
import com.securedoc.platform.application.telemetry.ProductTelemetryService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Objects;
import java.util.UUID;

/**
 * Owns the terminal state transition when distributed package-processing recovery is exhausted.
 * A recovery scan is not an attempt; an attempt is one acquired processing-lease generation.
 */
public final class PackageProcessingRecoveryService {
    private static final ActorId SYSTEM_ACTOR = ActorId.of(
            UUID.fromString("00000000-0000-0000-0000-000000000000"));

    private final PackageRepository packageRepository;
    private final PackageLifecycleService lifecycleService;
    private final ProcessingIncidentService incidentService;
    private final AuditService auditService;
    private final ProductTelemetryService productTelemetryService;

    public PackageProcessingRecoveryService(PackageRepository packageRepository,
                                            PackageLifecycleService lifecycleService,
                                            ProcessingIncidentService incidentService,
                                            AuditService auditService,
                                            ProductTelemetryService productTelemetryService) {
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.lifecycleService = Objects.requireNonNull(lifecycleService);
        this.incidentService = Objects.requireNonNull(incidentService);
        this.auditService = Objects.requireNonNull(auditService);
        this.productTelemetryService = Objects.requireNonNull(productTelemetryService);
    }

    public boolean exhaustIfStillProcessing(PackageId packageId,
                                            TenantId tenantId,
                                            int attempts,
                                            int maxAttempts,
                                            CorrelationId correlationId) {
        var pkg = packageRepository.findByIdAndTenantId(packageId, tenantId).orElse(null);
        if (pkg == null || pkg.status() != PackageStatus.PROCESSING) return false;

        pkg.fail();
        packageRepository.save(pkg);
        incidentService.recordRecoveryExhausted(tenantId, packageId, attempts, maxAttempts, correlationId);
        try {
            lifecycleService.recordTechnicalFailure(packageId, tenantId, correlationId);
        } catch (RuntimeException ignored) {
            // FAILED + incident state is authoritative; retention scheduling can be retried operationally.
        }
        try {
            productTelemetryService.recoveryExhausted(tenantId, packageId, attempts, maxAttempts);
        } catch (RuntimeException ignored) {
            // Telemetry is non-authoritative.
        }
        auditService.recordFailure(tenantId, SYSTEM_ACTOR, AuditAction.PACKAGE_PROCESSING_RECOVERY_EXHAUSTED,
                "DocumentPackage", packageId.toString(), correlationId,
                "Automatic package-processing recovery exhausted: attempts=" + attempts
                        + ", maxAttempts=" + maxAttempts);
        return true;
    }
}
