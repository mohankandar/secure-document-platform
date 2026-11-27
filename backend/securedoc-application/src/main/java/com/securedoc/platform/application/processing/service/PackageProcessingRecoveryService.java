package com.securedoc.platform.application.processing.service;

import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.licensing.LicenseEnforcementException;
import com.securedoc.platform.application.lifecycle.service.PackageLifecycleService;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.resilience.TechnicalFailureClassifier;
import com.securedoc.platform.domain.processing.ProcessingStage;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.application.technical.service.ProcessingIncidentService;
import com.securedoc.platform.application.telemetry.ProductTelemetryService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.LinkedHashMap;
import java.util.Map;
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
        return exhaust(packageId, tenantId, attempts, maxAttempts, correlationId,
                java.util.EnumSet.of(PackageStatus.PROCESSING));
    }

    /**
     * Task 0048: the same stop for a staged case. A Redact job after a review runs while the case is still
     * PENDING_REVIEW or IN_REVIEW, so a case in those statuses is stopped too (with the same incident, audit and code).
     */
    public boolean exhaustStagedCase(PackageId packageId,
                                     TenantId tenantId,
                                     int attempts,
                                     int maxAttempts,
                                     CorrelationId correlationId) {
        return exhaust(packageId, tenantId, attempts, maxAttempts, correlationId, java.util.EnumSet.of(
                PackageStatus.PROCESSING, PackageStatus.PENDING_REVIEW, PackageStatus.IN_REVIEW));
    }

    private boolean exhaust(PackageId packageId,
                            TenantId tenantId,
                            int attempts,
                            int maxAttempts,
                            CorrelationId correlationId,
                            java.util.Set<PackageStatus> from) {
        var pkg = packageRepository.findByIdAndTenantId(packageId, tenantId).orElse(null);
        if (pkg == null || !from.contains(pkg.status())) return false;

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
                "Automatic package-processing recovery exhausted: " + com.securedoc.platform.application.error.LvErrorCodes.auditDetail(com.securedoc.platform.domain.error.LvErrorCode.PRC_008,
                        "PROCESSING_RECOVERY_EXHAUSTED", "attempts=" + attempts + ", maxAttempts=" + maxAttempts));
        return true;
    }

    /**
     * Task 0112: a staged case in PROCESSING that would never move again (a document waits for redaction, its run
     * finished, nothing is planned) stops: FAILED (shown as Stopped), a package incident and an audit failure with
     * LV-PRC-011. Never silently stuck; nothing is delivered.
     */
    public boolean stopNothingPlanned(PackageId packageId, TenantId tenantId, CorrelationId correlationId) {
        var pkg = packageRepository.findByIdAndTenantId(packageId, tenantId).orElse(null);
        if (pkg == null || pkg.status() != PackageStatus.PROCESSING) return false;
        pkg.fail();
        packageRepository.save(pkg);
        incidentService.recordNothingPlanned(tenantId, packageId, correlationId);
        try {
            lifecycleService.recordTechnicalFailure(packageId, tenantId, correlationId);
        } catch (RuntimeException ignored) {
            // FAILED + incident state is authoritative; retention scheduling can be retried operationally.
        }
        auditService.recordFailure(tenantId, SYSTEM_ACTOR, AuditAction.PACKAGE_PROCESSING_RECOVERY_EXHAUSTED,
                "DocumentPackage", packageId.toString(), correlationId,
                "Processing stopped, nothing planned: " + com.securedoc.platform.application.error.LvErrorCodes.auditDetail(
                        com.securedoc.platform.domain.error.LvErrorCode.PRC_011, "NOTHING_PLANNED",
                        "documentsWaitingForRedaction"));
        return true;
    }

    /**
     * Task 0023 C1: a licence refusal is final for this pass and is not retried automatically.
     * A licence configuration error (the licence service itself is broken, HTTP 503) is not a
     * refusal and is counted like any other dispatch failure.
     */
    public static boolean isLicenceDenial(Throwable failure) {
        return failure instanceof LicenseEnforcementException licence && !licence.serviceUnavailable();
    }

    /**
     * Task 0023 C1: stops a case whose processing could not be started, instead of letting the
     * recovery scan redispatch it every few seconds forever. Called by the dispatcher after the
     * configured number of failed dispatches of the same case, or at once for a licence denial.
     *
     * <p>Only SUBMITTED cases (and PROCESSING cases refused by the licence) are stopped here.
     * Other PROCESSING failures keep the durable recovery budget ({@link #exhaustIfStillProcessing}),
     * and review/approval continuations are human-triggered, so V1 behaviour for those is unchanged.
     *
     * <p>Writes the standard stop: package FAILED, a Support incident with the code, one
     * {@code DOCUMENT_PROCESSING_FAILED} audit row on the package, and the technical hold.
     * Nothing from the exception message is stored; only its simple type name.
     *
     * @return true when the case was stopped by this call
     */
    public boolean stopAfterDispatchFailure(PackageId packageId,
                                            TenantId tenantId,
                                            Throwable failure,
                                            int failedAttempts,
                                            int maxAttempts,
                                            CorrelationId correlationId) {
        Objects.requireNonNull(failure);
        var pkg = packageRepository.findByIdAndTenantId(packageId, tenantId).orElse(null);
        if (pkg == null) return false;
        boolean licenceDenial = isLicenceDenial(failure);
        boolean applicable = pkg.status() == PackageStatus.SUBMITTED
                || (licenceDenial && pkg.status() == PackageStatus.PROCESSING);
        if (!applicable) return false;

        TechnicalFailureCode code = licenceDenial
                ? TechnicalFailureCode.PROCESSING_LICENSE_DENIED
                : TechnicalFailureCode.PROCESSING_DISPATCH_EXHAUSTED;
        TechnicalDependencyException stop = new TechnicalDependencyException(
                TechnicalDependency.PROCESSING, code, false, "Package processing stopped")
                .withAttempts(Math.max(1, failedAttempts));
        if (licenceDenial) {
            // Task 0025: the licence refusal gives the precise code (LV-LIC-001 to LV-LIC-004); behaviour is unchanged.
            var licenceCode = com.securedoc.platform.application.error.LvErrorCodes.optional(failure);
            if (licenceCode.isPresent()) stop = stop.withLvCode(licenceCode.get());
        }

        pkg.fail();
        packageRepository.save(pkg);

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("scope", "PACKAGE");
        metadata.put("failureType", failure.getClass().getSimpleName());
        metadata.put("maxAttempts", String.valueOf(maxAttempts));
        if (failure instanceof LicenseEnforcementException licence) {
            metadata.put("licenceErrorCode", licence.errorCode());
            if (licence.status() != null) metadata.put("licenceStatus", licence.status().name());
        }
        incidentService.recordFailure(tenantId, packageId, null, ProcessingStage.DOCUMENT_PROCESSING, stop,
                correlationId, licenceDenial
                        ? "Processing stopped: the tenant licence does not allow document processing"
                        : "Processing stopped: it could not be started after repeated tries",
                metadata);
        try {
            lifecycleService.recordTechnicalFailure(packageId, tenantId, correlationId);
        } catch (RuntimeException ignored) {
            // FAILED + incident state is authoritative; retention scheduling can be retried operationally.
        }
        auditService.recordFailure(tenantId, SYSTEM_ACTOR, AuditAction.DOCUMENT_PROCESSING_FAILED,
                "DocumentPackage", packageId.toString(), correlationId,
                TechnicalFailureClassifier.safeAuditDetail(stop, "Package processing stopped"));
        return true;
    }
}
