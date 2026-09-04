package com.securedoc.platform.application.intake.external;

import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.ExternalIntakeRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.StoragePort;
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
    private final StoragePort storagePort;
    private final AuditService auditService;
    private final Clock clock;

    public ExternalIntakeMaintenanceService(ExternalIntakeRepository externalIntakeRepository,
                                            PackageRepository packageRepository,
                                            ArtifactRepository artifactRepository,
                                            StoragePort storagePort,
                                            AuditService auditService) {
        this(externalIntakeRepository, packageRepository, artifactRepository, storagePort, auditService,
                Clock.systemUTC());
    }

    ExternalIntakeMaintenanceService(ExternalIntakeRepository externalIntakeRepository,
                                     PackageRepository packageRepository,
                                     ArtifactRepository artifactRepository,
                                     StoragePort storagePort,
                                     AuditService auditService,
                                     Clock clock) {
        this.externalIntakeRepository = Objects.requireNonNull(externalIntakeRepository);
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.artifactRepository = Objects.requireNonNull(artifactRepository);
        this.storagePort = Objects.requireNonNull(storagePort);
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
                    if (artifact.storageReference() != null) storagePort.delete(artifact.storageReference());
                    artifact.delete();
                    artifactRepository.save(artifact);
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
                    "Expired incomplete intake cleanup failed; cleanup remains retryable");
            return false;
        }
    }

    public record MaintenanceResult(int expiredIntakes, int cleanedIntakes) { }
}
