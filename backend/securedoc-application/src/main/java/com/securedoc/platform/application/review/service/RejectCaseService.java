package com.securedoc.platform.application.review.service;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.lifecycle.service.PackageLifecycleService;
import com.securedoc.platform.application.port.out.PackageRejectionRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.PipelineJobRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.review.RejectionReason;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Task 0071 (BL8): a person who may review (or approve) ends a case as Rejected, with a reason from the fixed list.
 * Nothing is exported, open processing jobs of the case are removed, its files follow the normal keeping time, and it
 * can't be undone. Run it in one transaction: the status, the reason, the keeping time and the audit record together.
 */
public final class RejectCaseService {

    /** Where the person rejects it: each place has its own permission and its own case states. */
    public enum Step {
        REVIEW(Set.of(PackageStatus.PENDING_REVIEW, PackageStatus.IN_REVIEW)),
        APPROVAL(Set.of(PackageStatus.PENDING_FINAL_APPROVAL));

        private final Set<PackageStatus> from;

        Step(Set<PackageStatus> from) {
            this.from = from;
        }
    }

    private final PackageRepository packages;
    private final PackageRejectionRepository rejections;
    private final PackageLifecycleService lifecycle;
    private final PipelineJobRepository jobs;
    private final AuditService audit;

    public RejectCaseService(PackageRepository packages, PackageRejectionRepository rejections,
                             PackageLifecycleService lifecycle, PipelineJobRepository jobs, AuditService audit) {
        this.packages = Objects.requireNonNull(packages);
        this.rejections = Objects.requireNonNull(rejections);
        this.lifecycle = Objects.requireNonNull(lifecycle);
        this.jobs = Objects.requireNonNull(jobs);
        this.audit = Objects.requireNonNull(audit);
    }

    public RejectionReason reject(PackageId packageId, TenantId tenantId, ActorId actorId, CorrelationId correlationId,
                                  Step step, String reasonCode) {
        RejectionReason reason = RejectionReason.fromCode(reasonCode == null ? "" : reasonCode.trim())
                .orElseThrow(() -> new ValidationException(LvErrorCode.INT_020, "Choose a reason from the list"));
        packages.lockForUpdate(packageId, tenantId);
        DocumentPackage pkg = packages.findByIdAndTenantId(packageId, tenantId)
                .orElseThrow(() -> new NotFoundException("DocumentPackage", packageId.toString()));
        if (!step.from.contains(pkg.status())) throw notHere();
        int cycle = lifecycle.nextExportCycle(packageId, tenantId);
        pkg.reject();
        if (!packages.changeStatus(pkg, step.from)) throw notHere();
        // A running step of the case finds its job gone and saves nothing; a waiting one is never started.
        jobs.removeStageJobs(tenantId, packageId, cycle);
        rejections.save(tenantId, packageId, reason, actorId, Instant.now());
        // Never exported: kept as long as a stopped case. Exported before: its own dates stand.
        lifecycle.recordTechnicalFailure(packageId, tenantId, correlationId);
        audit.recordSuccess(tenantId, actorId, AuditAction.PACKAGE_REJECTED, "DocumentPackage", packageId.toString(),
                packageId, correlationId, "Case rejected: code=" + reason.code().code() + ", step=" + step);
        return reason;
    }

    private static ValidationException notHere() {
        return new ValidationException(LvErrorCode.REV_002, "The case is not waiting at this step any more");
    }
}
