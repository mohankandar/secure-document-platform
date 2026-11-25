package com.securedoc.platform.application.lifecycle.service;

import com.securedoc.platform.application.exception.ApplicationException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.lifecycle.command.ApproveFinalDeliveryCommand;
import com.securedoc.platform.application.port.in.ApproveFinalDeliveryUseCase;
import com.securedoc.platform.application.port.out.CaseReviewersPort;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Task 0076 (C1): approves several cases of one entity, each as its own action: its own check, audit record and export.
 * One that fails stays where it is, with its reason; the others go on. Four eyes: a case the person reviewed themselves
 * is refused here, whatever the screen offered. Only while the entity's approval before delivery is on.
 */
public final class BulkApprovalService {
    public static final int MAX_CASES = 50;
    private static final Logger log = LoggerFactory.getLogger(BulkApprovalService.class);

    /** {@code reason} null: approved. */
    public record Outcome(PackageId packageId, LvErrorCode reason) {
        public boolean approved() {
            return reason == null;
        }
    }

    /** What the screen may offer. */
    public record Offer(boolean enabled, Set<PackageId> reviewedByYou) {
    }

    private final ApproveFinalDeliveryUseCase approval;
    private final PackageLifecycleService lifecycle;
    private final CaseReviewersPort reviewers;
    private final AuditService audit;

    public BulkApprovalService(ApproveFinalDeliveryUseCase approval, PackageLifecycleService lifecycle,
                               CaseReviewersPort reviewers, AuditService audit) {
        this.approval = Objects.requireNonNull(approval);
        this.lifecycle = Objects.requireNonNull(lifecycle);
        this.reviewers = Objects.requireNonNull(reviewers);
        this.audit = Objects.requireNonNull(audit);
    }

    public Offer offer(TenantId tenantId, ActorId person, Collection<PackageId> cases) {
        boolean enabled = lifecycle.policyFor(tenantId).finalApprovalRequired();
        return new Offer(enabled, enabled ? reviewers.reviewedBy(tenantId, person, cases) : Set.of());
    }

    public List<Outcome> approve(TenantId tenantId, ActorId person, CorrelationId correlationId, List<PackageId> requested) {
        Set<PackageId> cases = new LinkedHashSet<>(requested);
        if (cases.isEmpty() || cases.size() > MAX_CASES) {
            throw new ValidationException(LvErrorCode.INT_020, "Choose 1 to " + MAX_CASES + " cases");
        }
        if (!lifecycle.policyFor(tenantId).finalApprovalRequired()) {
            throw new ValidationException(LvErrorCode.REV_002, "Approval before delivery is off for this entity");
        }
        Set<PackageId> own = reviewers.reviewedBy(tenantId, person, cases);
        String bulkAction = UUID.randomUUID() + ", cases=" + cases.size();
        List<Outcome> outcomes = new ArrayList<>();
        for (PackageId id : cases) outcomes.add(new Outcome(id, one(id, tenantId, person, correlationId, own, bulkAction)));
        return outcomes;
    }

    private LvErrorCode one(PackageId id, TenantId tenantId, ActorId person, CorrelationId correlationId,
                            Set<PackageId> own, String bulkAction) {
        LvErrorCode reason;
        if (own.contains(id)) {
            reason = LvErrorCode.REV_015;
        } else {
            try {
                approval.execute(new ApproveFinalDeliveryCommand(id, tenantId, person, correlationId, bulkAction));
                return null;
            } catch (ApplicationException refused) {
                reason = refused.lvCode() == null ? LvErrorCode.SYS_001 : refused.lvCode();
            } catch (RuntimeException failed) {
                log.error("BULK_APPROVAL_CASE_FAILED package={} exceptionType={}", id, failed.getClass().getSimpleName());
                reason = LvErrorCode.SYS_001;
            }
        }
        audit.recordFailure(tenantId, person, AuditAction.PACKAGE_FINAL_APPROVED, "DocumentPackage", id.toString(), id,
                correlationId, "Final delivery not approved: code=" + reason.code() + ", bulkAction=" + bulkAction);
        return reason;
    }
}
