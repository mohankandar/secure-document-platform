package com.securedoc.platform.application.intake;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.CasePriorityRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.PipelinePriority;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Task 0073: Urgent, Normal or Low: at intake, as a source's default, and changed by a reviewer. */
public final class CasePriorityService {
    private final CasePriorityRepository repository;
    private final PackageRepository packages;
    private final AuditService auditService;
    private BulkSourceService bulk;

    public CasePriorityService(CasePriorityRepository repository, PackageRepository packages, AuditService auditService) {
        this.repository = Objects.requireNonNull(repository);
        this.packages = Objects.requireNonNull(packages);
        this.auditService = Objects.requireNonNull(auditService);
    }

    /** Task 0079: the same service; the cases of a Bulk source are Bulk. */
    public CasePriorityService withBulk(BulkSourceService bulk) {
        this.bulk = Objects.requireNonNull(bulk);
        return this;
    }

    /** A watched folder's source key. */
    public static String folder(String key) {
        return "folder:" + key;
    }

    /** Null when none was sent. Anything but the three names is refused, not guessed. */
    public static PipelinePriority read(String sent) {
        if (sent == null || sent.isBlank()) return null;
        return switch (sent.trim().toUpperCase(Locale.ROOT)) {
            case "URGENT" -> PipelinePriority.URGENT;
            case "NORMAL" -> PipelinePriority.NORMAL;
            case "LOW" -> PipelinePriority.LOW;
            default -> throw new ValidationException(LvErrorCode.INT_015, "priority must be URGENT, NORMAL or LOW");
        };
    }

    /** Bulk for a Bulk source, whatever was sent; else what was sent, else the source's default, else Normal. */
    public PipelinePriority atIntake(TenantId tenantId, PackageId packageId, PipelinePriority sent, String sourceKey) {
        PipelinePriority priority = bulk != null && bulk.bulk(tenantId, sourceKey) ? PipelinePriority.BULK
                : sent != null ? sent : sourceKey == null ? PipelinePriority.NORMAL
                : repository.sourceDefault(tenantId, sourceKey).orElse(PipelinePriority.NORMAL);
        repository.saveNew(tenantId, packageId, priority, sourceKey);
        return priority;
    }

    /** Must run inside a transaction. */
    public PipelinePriority change(TenantId tenantId, ActorId actorId, PackageId packageId, PipelinePriority priority,
                                   CorrelationId correlationId) {
        packages.findByIdAndTenantId(packageId, tenantId)
                .orElseThrow(() -> new NotFoundException("DocumentPackage", packageId.toString()));
        PipelinePriority before = repository.change(tenantId, packageId, priority);
        if (before != priority) {
            auditService.recordSuccess(tenantId, actorId, AuditAction.CASE_PRIORITY_CHANGED, "DocumentPackage",
                    packageId.toString(), packageId, correlationId, "priority=" + before.name() + "->" + priority.name());
        }
        return priority;
    }

    public Map<PackageId, PipelinePriority> find(TenantId tenantId, Collection<PackageId> packageIds) {
        return repository.find(tenantId, packageIds);
    }

    public Map<String, PipelinePriority> sourceDefaults(TenantId tenantId) {
        return repository.sourceDefaults(tenantId);
    }

    /** Cases already taken in keep theirs. */
    public void setSourceDefault(TenantId tenantId, ActorId actorId, String sourceKey, PipelinePriority priority,
                                 CorrelationId correlationId) {
        PipelinePriority before = repository.sourceDefault(tenantId, sourceKey).orElse(PipelinePriority.NORMAL);
        repository.saveSourceDefault(tenantId, sourceKey, priority);
        if (before != priority) {
            auditService.recordSuccess(tenantId, actorId, AuditAction.SOURCE_PRIORITY_CHANGED, "IntakeSource", sourceKey,
                    correlationId, "priority=" + before.name() + "->" + priority.name());
        }
    }
}
