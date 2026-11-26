package com.securedoc.platform.application.intake.external;

import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.ExternalIntakeRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.ErasurePort;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactStatus;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.intake.ExternalIntake;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Owns expiry and protected-binary cleanup for incomplete external machine intakes.
 * Metadata and audit history are intentionally retained.
 */
public final class ExternalIntakeMaintenanceService {
    private final ExternalIntakeRepository externalIntakeRepository;
    private final PackageRepository packageRepository;
    private final ArtifactRepository artifactRepository;
    private final ErasurePort erasure;
    private final AuditService auditService;
    private final Clock clock;

    public ExternalIntakeMaintenanceService(ExternalIntakeRepository externalIntakeRepository,
                                            PackageRepository packageRepository,
                                            ArtifactRepository artifactRepository,
                                            ErasurePort erasure,
                                            AuditService auditService) {
        this(externalIntakeRepository, packageRepository, artifactRepository, erasure, auditService,
                Clock.systemUTC());
    }

    ExternalIntakeMaintenanceService(ExternalIntakeRepository externalIntakeRepository,
                                     PackageRepository packageRepository,
                                     ArtifactRepository artifactRepository,
                                     ErasurePort erasure,
                                     AuditService auditService,
                                     Clock clock) {
        this.externalIntakeRepository = Objects.requireNonNull(externalIntakeRepository);
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.artifactRepository = Objects.requireNonNull(artifactRepository);
        this.erasure = Objects.requireNonNull(erasure);
        this.auditService = Objects.requireNonNull(auditService);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Expires one intake if its fixed completion deadline has passed and attempts cleanup. */
    public boolean expireIfDue(ExternalIntake intake, ActorId actorId, CorrelationId correlationId) {
        Objects.requireNonNull(intake);
        Instant now = Instant.now(clock);
        if (!intake.expireIfDue(now)) return false;

        externalIntakeRepository.save(intake);
        packageRepository.findByIdAndTenantId(intake.packageId(), intake.tenantId()).ifPresent(pkg -> {
            if (pkg.status() == PackageStatus.CREATED) {
                pkg.cancel();
                packageRepository.save(pkg);
            }
        });
        auditService.recordSuccess(intake.tenantId(), actorId, AuditAction.EXTERNAL_INTAKE_EXPIRED,
                "ExternalIntake", intake.packageId().toString(), correlationId,
                "Incomplete external intake expired: received=" + intake.receivedCount() + "/" + intake.expectedCount());
        attemptCleanup(intake, actorId, correlationId);
        return true;
    }

    /** Task 0102: hides a refused attempt's case from the dashboard. Without it the case is only cancelled. */
    private com.securedoc.platform.application.port.out.PackageDashboardVisibilityPort dashboardVisibility;

    public ExternalIntakeMaintenanceService withDashboardVisibility(
            com.securedoc.platform.application.port.out.PackageDashboardVisibilityPort dashboardVisibility) {
        this.dashboardVisibility = Objects.requireNonNull(dashboardVisibility);
        return this;
    }

    /**
     * Task 0102 (owner test U3): a polled case is all or nothing. When one of its files is refused for good the attempt
     * ends now: its case is hidden from the dashboard first, then cancelled (kept for reports), and the files already
     * stored are erased as at expiry. Each step can be repeated, so a crash between them is finished by the next refusal
     * or by the cleanup retry.
     */
    public boolean refuseNow(ExternalIntake intake, ActorId actorId, CorrelationId correlationId, String lvCode) {
        Objects.requireNonNull(intake);
        Instant now = Instant.now(clock);
        packageRepository.findByIdAndTenantId(intake.packageId(), intake.tenantId()).ifPresent(pkg -> {
            if (pkg.status() != PackageStatus.CREATED) return;
            if (dashboardVisibility != null) dashboardVisibility.hide(pkg.id(), pkg.tenantId(), actorId, now);
            pkg.cancel();
            packageRepository.save(pkg);
        });
        if (!intake.refuse(now)) return false;
        externalIntakeRepository.save(intake);
        auditService.recordSuccess(intake.tenantId(), actorId, AuditAction.EXTERNAL_INTAKE_EXPIRED,
                "ExternalIntake", intake.packageId().toString(), correlationId,
                "Refused at intake: code=" + lvCode + ", received=" + intake.receivedCount() + "/" + intake.expectedCount());
        attemptCleanup(intake, actorId, correlationId);
        return true;
    }

    /** Expires due intakes and retries cleanup for already-expired intakes whose prior cleanup failed. */
    public MaintenanceResult maintainDue(int limit, ActorId actorId, CorrelationId correlationId) {
        int expired = 0;
        int cleaned = 0;
        Instant now = Instant.now(clock);
        for (ExternalIntake intake : externalIntakeRepository.findAwaitingExpiredBefore(now, limit)) {
            if (expireIfDue(intake, actorId, correlationId)) {
                expired++;
                if (intake.cleanupCompletedAt() != null) cleaned++;
            }
        }
        for (ExternalIntake intake : externalIntakeRepository.findExpiredCleanupPending(limit)) {
            if (intake.cleanupCompletedAt() == null && attemptCleanup(intake, actorId, correlationId)) cleaned++;
        }
        return new MaintenanceResult(expired, cleaned);
    }

    private boolean attemptCleanup(ExternalIntake intake, ActorId actorId, CorrelationId correlationId) {
        if (intake.cleanupCompletedAt() != null) return true;
        try {
            for (var manifestDocument : intake.documents()) {
                if (!manifestDocument.received()) continue;
                for (Artifact artifact : artifactRepository.findByDocumentIdAndTenantId(
                        manifestDocument.receivedDocumentId(), intake.tenantId())) {
                    if (artifact.status() == ArtifactStatus.DELETED) continue;
                    erasure.eraseArtifact(artifact, ErasurePort.Reason.rule("intake-expired"));
                }
            }
            intake.markCleanupCompleted(Instant.now(clock));
            externalIntakeRepository.save(intake);
            auditService.recordSuccess(intake.tenantId(), actorId, AuditAction.EXTERNAL_INTAKE_CLEANED,
                    "ExternalIntake", intake.packageId().toString(), correlationId,
                    "Expired incomplete intake protected binaries cleaned; metadata retained");
            return true;
        } catch (RuntimeException ex) {
            auditService.recordFailure(intake.tenantId(), actorId, AuditAction.EXTERNAL_INTAKE_CLEANED,
                    "ExternalIntake", intake.packageId().toString(), correlationId,
                    "Expired incomplete intake cleanup failed; cleanup remains retryable: " + com.securedoc.platform.application.error.LvErrorCodes.auditDetail(com.securedoc.platform.domain.error.LvErrorCode.SYS_007,
                            ex.getClass().getSimpleName(), null));
            return false;
        }
    }

    public record MaintenanceResult(int expiredIntakes, int cleanedIntakes) { }
}
