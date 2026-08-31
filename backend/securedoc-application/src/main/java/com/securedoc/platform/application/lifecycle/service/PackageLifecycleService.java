package com.securedoc.platform.application.lifecycle.service;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.lifecycle.command.ExtendTechnicalHoldCommand;
import com.securedoc.platform.application.lifecycle.command.PurgeDueRetentionCommand;
import com.securedoc.platform.application.lifecycle.result.PackageLifecycleSummary;
import com.securedoc.platform.application.lifecycle.result.RetentionPurgeResult;
import com.securedoc.platform.application.port.in.ExtendTechnicalHoldUseCase;
import com.securedoc.platform.application.port.in.GetPackageLifecycleQuery;
import com.securedoc.platform.application.port.in.PurgeDueRetentionUseCase;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.PackageExportRecordRepository;
import com.securedoc.platform.application.port.out.PackageLifecycleRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.port.out.TenantLifecyclePolicyRepository;
import com.securedoc.platform.application.resilience.TechnicalFailureClassifier;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactStatus;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.lifecycle.PackageExportRecord;
import com.securedoc.platform.domain.lifecycle.PackageLifecycle;
import com.securedoc.platform.domain.lifecycle.TenantLifecyclePolicy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Owns package export-cycle accounting, temporary-artifact retention, purge and final-approval state.
 * Customer delivery remains durable at the configured destination; this service manages only LexaVault-owned artifacts.
 */
public final class PackageLifecycleService implements GetPackageLifecycleQuery,
        ExtendTechnicalHoldUseCase, PurgeDueRetentionUseCase {

    private final TenantLifecyclePolicyRepository policyRepository;
    private final PackageLifecycleRepository lifecycleRepository;
    private final PackageExportRecordRepository exportRecordRepository;
    private final PackageRepository packageRepository;
    private final DocumentRepository documentRepository;
    private final ArtifactRepository artifactRepository;
    private final StoragePort storagePort;
    private final AuditService auditService;

    public PackageLifecycleService(TenantLifecyclePolicyRepository policyRepository,
                                   PackageLifecycleRepository lifecycleRepository,
                                   PackageExportRecordRepository exportRecordRepository,
                                   PackageRepository packageRepository,
                                   DocumentRepository documentRepository,
                                   ArtifactRepository artifactRepository,
                                   StoragePort storagePort,
                                   AuditService auditService) {
        this.policyRepository = Objects.requireNonNull(policyRepository);
        this.lifecycleRepository = Objects.requireNonNull(lifecycleRepository);
        this.exportRecordRepository = Objects.requireNonNull(exportRecordRepository);
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.artifactRepository = Objects.requireNonNull(artifactRepository);
        this.storagePort = Objects.requireNonNull(storagePort);
        this.auditService = Objects.requireNonNull(auditService);
    }

    public TenantLifecyclePolicy policyFor(TenantId tenantId) {
        return policyRepository.findByTenantId(tenantId).orElseGet(() -> TenantLifecyclePolicy.defaults(tenantId));
    }

    public PackageLifecycle lifecycleFor(PackageId packageId, TenantId tenantId) {
        ensurePackageExists(packageId, tenantId);
        return lifecycleRepository.findByPackageIdAndTenantId(packageId, tenantId)
                .orElseGet(() -> PackageLifecycle.create(packageId, tenantId));
    }

    /** Returns the next business-successful export cycle without consuming it. */
    public int nextExportCycle(PackageId packageId, TenantId tenantId) {
        return lifecycleFor(packageId, tenantId).nextExportCycle();
    }

    /** True only when policy requires approval and this export cycle has not already been approved. */
    public boolean requiresFinalApproval(PackageId packageId, TenantId tenantId) {
        TenantLifecyclePolicy policy = policyFor(tenantId);
        if (!policy.finalApprovalRequired()) return false;
        return !lifecycleFor(packageId, tenantId).finalApprovalGranted();
    }

    public void grantFinalApproval(PackageId packageId, TenantId tenantId, Instant now) {
        PackageLifecycle lifecycle = lifecycleFor(packageId, tenantId);
        lifecycle.grantFinalApproval(now);
        lifecycleRepository.save(lifecycle);
    }

    public void clearFinalApproval(PackageId packageId, TenantId tenantId, Instant now) {
        PackageLifecycle lifecycle = lifecycleFor(packageId, tenantId);
        lifecycle.clearFinalApproval(now);
        lifecycleRepository.save(lifecycle);
    }

    public void recordSuccessfulExport(PackageId packageId,
                                       TenantId tenantId,
                                       int cycleNumber,
                                       String destinationType,
                                       List<PackageExportRecord.ExportedDocument> documents,
                                       CorrelationId correlationId) {
        Instant exportedAt = Instant.now();
        PackageLifecycle lifecycle = lifecycleFor(packageId, tenantId);
        TenantLifecyclePolicy policy = policyFor(tenantId);
        lifecycle.recordSuccessfulExport(cycleNumber, exportedAt, policy);
        PackageExportRecord record = PackageExportRecord.create(
                tenantId, packageId, cycleNumber, destinationType, exportedAt, correlationId, documents);
        exportRecordRepository.save(record);
        lifecycleRepository.save(lifecycle);

        auditService.recordSuccess(
                tenantId, systemActor(), AuditAction.PACKAGE_EXPORT_RECORDED,
                "DocumentPackage", packageId.toString(), correlationId,
                "Successful export recorded: cycle=" + cycleNumber + ", destination=" + destinationType
                        + ", documents=" + documents.size());
        auditService.recordSuccess(
                tenantId, systemActor(), AuditAction.RETENTION_SCHEDULED,
                "DocumentPackage", packageId.toString(), correlationId,
                "Retention scheduled: originalDeleteAt=" + lifecycle.originalDeleteAt().orElse(null)
                        + ", redactedDeleteAt=" + lifecycle.redactedDeleteAt().orElse(null));
    }

    /** Starts a bounded hold only when this package has never successfully exported. */
    public void recordTechnicalFailure(PackageId packageId, TenantId tenantId, CorrelationId correlationId) {
        PackageLifecycle lifecycle = lifecycleFor(packageId, tenantId);
        if (lifecycle.ensureNeverExportedTechnicalHold(Instant.now(), policyFor(tenantId))) {
            lifecycleRepository.save(lifecycle);
            auditService.recordSuccess(
                    tenantId, systemActor(), AuditAction.RETENTION_SCHEDULED,
                    "DocumentPackage", packageId.toString(), correlationId,
                    "Never-exported technical hold scheduled: holdUntil="
                            + lifecycle.technicalHoldUntil().orElse(null));
        }
    }

    @Override
    public PackageLifecycleSummary execute(PackageId packageId, TenantId tenantId) {
        PackageLifecycle lifecycle = lifecycleFor(packageId, tenantId);
        TenantLifecyclePolicy policy = policyFor(tenantId);
        String lastDestination = exportRecordRepository.findByPackageIdAndTenantId(packageId, tenantId).stream()
                .max(Comparator.comparingInt(PackageExportRecord::cycleNumber))
                .map(PackageExportRecord::destinationType)
                .orElse(null);
        return toSummary(lifecycle, policy, lastDestination, Instant.now());
    }

    @Override
    public PackageLifecycleSummary execute(ExtendTechnicalHoldCommand command) {
        if (command.holdHoursFromNow() < TenantLifecyclePolicy.DEFAULT_NEVER_EXPORTED_HOLD_HOURS
                || command.holdHoursFromNow() > TenantLifecyclePolicy.MAX_NEVER_EXPORTED_HOLD_HOURS) {
            throw new ValidationException("Technical hold must be between 72 hours and 14 days");
        }
        DocumentPackage pkg = ensurePackageExists(command.packageId(), command.tenantId());
        if (pkg.status() != PackageStatus.FAILED) {
            throw new ValidationException("Technical hold can only be extended for a failed package");
        }
        PackageLifecycle lifecycle = lifecycleFor(command.packageId(), command.tenantId());
        if (lifecycle.successfulExportCycles() > 0) {
            throw new ValidationException("Technical hold extension applies only before the first successful export");
        }
        Instant now = Instant.now();
        Instant requestedUntil = now.plus(command.holdHoursFromNow(), ChronoUnit.HOURS);
        try {
            lifecycle.extendTechnicalHold(now, requestedUntil);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ValidationException(e.getMessage());
        }
        lifecycleRepository.save(lifecycle);
        auditService.recordSuccess(command.tenantId(), command.actorId(), AuditAction.RETENTION_HOLD_EXTENDED,
                "DocumentPackage", command.packageId().toString(), command.correlationId(),
                "Technical hold extended: holdUntil=" + requestedUntil);
        return execute(command.packageId(), command.tenantId());
    }

    @Override
    public RetentionPurgeResult execute(PurgeDueRetentionCommand command) {
        int redactedPurged = 0;
        int fullyPurged = 0;

        for (PackageLifecycle lifecycle : lifecycleRepository.findDueRedactedPurge(command.now(), command.limit())) {
            if (!isTerminalForPurge(lifecycle)) continue;
            try {
                purgeArtifactType(lifecycle, ArtifactType.REDACTED);
                lifecycle.markRedactedPurged(command.now());
                lifecycleRepository.save(lifecycle);
                redactedPurged++;
                auditService.recordSuccess(lifecycle.tenantId(), command.actorId(), AuditAction.REDACTED_ARTIFACT_PURGED,
                        "DocumentPackage", lifecycle.packageId().toString(), command.correlationId(),
                        "Temporary internal redacted artifacts purged");
            } catch (Exception e) {
                auditService.recordFailure(lifecycle.tenantId(), command.actorId(), AuditAction.REDACTED_ARTIFACT_PURGED,
                        "DocumentPackage", lifecycle.packageId().toString(), command.correlationId(),
                        TechnicalFailureClassifier.safeAuditDetail(e, "Redacted artifact purge failed"));
            }
        }

        for (PackageLifecycle lifecycle : lifecycleRepository.findDueFinalPurge(command.now(), command.limit())) {
            if (!isTerminalForPurge(lifecycle)) continue;
            try {
                purgeAllInternalArtifacts(lifecycle);
                lifecycle.markFinalPurged(command.now());
                lifecycleRepository.save(lifecycle);
                fullyPurged++;
                auditService.recordSuccess(lifecycle.tenantId(), command.actorId(), AuditAction.ORIGINAL_ARTIFACT_PURGED,
                        "DocumentPackage", lifecycle.packageId().toString(), command.correlationId(),
                        "Original and remaining internal working artifacts purged");
                auditService.recordSuccess(lifecycle.tenantId(), command.actorId(), AuditAction.PACKAGE_PURGED,
                        "DocumentPackage", lifecycle.packageId().toString(), command.correlationId(),
                        "Internal artifact lifecycle completed; customer delivery remains at destination");
            } catch (Exception e) {
                auditService.recordFailure(lifecycle.tenantId(), command.actorId(), AuditAction.PACKAGE_PURGED,
                        "DocumentPackage", lifecycle.packageId().toString(), command.correlationId(),
                        TechnicalFailureClassifier.safeAuditDetail(e, "Package artifact purge failed"));
            }
        }
        return new RetentionPurgeResult(redactedPurged, fullyPurged);
    }

    public boolean canReprocess(PackageId packageId, TenantId tenantId) {
        return lifecycleFor(packageId, tenantId).canReprocess(Instant.now());
    }

    private boolean isTerminalForPurge(PackageLifecycle lifecycle) {
        DocumentPackage pkg = packageRepository.findByIdAndTenantId(lifecycle.packageId(), lifecycle.tenantId()).orElse(null);
        if (pkg == null) return false;
        return switch (pkg.status()) {
            case COMPLETED, FAILED, REJECTED, CANCELLED, VAULTED, APPROVED -> true;
            default -> false;
        };
    }

    private void purgeArtifactType(PackageLifecycle lifecycle, ArtifactType artifactType) {
        for (Document document : documentRepository.findByPackageIdAndTenantId(lifecycle.packageId(), lifecycle.tenantId())) {
            for (Artifact artifact : artifactRepository.findByDocumentIdAndTenantId(document.id(), lifecycle.tenantId())) {
                if (artifact.artifactType() == artifactType && artifact.status() != ArtifactStatus.DELETED) {
                    deleteArtifact(artifact);
                }
            }
        }
    }

    private void purgeAllInternalArtifacts(PackageLifecycle lifecycle) {
        for (Document document : documentRepository.findByPackageIdAndTenantId(lifecycle.packageId(), lifecycle.tenantId())) {
            for (Artifact artifact : artifactRepository.findByDocumentIdAndTenantId(document.id(), lifecycle.tenantId())) {
                if (artifact.status() != ArtifactStatus.DELETED) deleteArtifact(artifact);
            }
        }
    }

    public void deleteArtifact(Artifact artifact) {
        if (artifact.storageReference() != null) {
            storagePort.delete(artifact.storageReference());
        }
        artifact.delete();
        artifactRepository.save(artifact);
    }

    private DocumentPackage ensurePackageExists(PackageId packageId, TenantId tenantId) {
        return packageRepository.findByIdAndTenantId(packageId, tenantId)
                .orElseThrow(() -> new NotFoundException("DocumentPackage", packageId.toString()));
    }

    private PackageLifecycleSummary toSummary(PackageLifecycle lifecycle, TenantLifecyclePolicy policy,
                                              String lastDestination, Instant now) {
        return new PackageLifecycleSummary(
                lifecycle.packageId(), lifecycle.successfulExportCycles(),
                TenantLifecyclePolicy.MAX_SUCCESSFUL_EXPORT_CYCLES,
                lifecycle.canReprocess(now), policy.finalApprovalRequired(), lifecycle.finalApprovalGranted(),
                lifecycle.lastExportedAt().orElse(null), lifecycle.originalDeleteAt().orElse(null),
                lifecycle.redactedDeleteAt().orElse(null), lifecycle.technicalHoldUntil().orElse(null),
                lifecycle.redactedPurgedAt().orElse(null), lifecycle.originalPurgedAt().orElse(null),
                lifecycle.purgedAt().orElse(null), lastDestination);
    }

    private ActorId systemActor() {
        return ActorId.of(java.util.UUID.fromString("00000000-0000-0000-0000-000000000000"));
    }
}
