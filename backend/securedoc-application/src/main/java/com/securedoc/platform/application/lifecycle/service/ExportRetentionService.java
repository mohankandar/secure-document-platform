package com.securedoc.platform.application.lifecycle.service;

import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.DerivedDeliveryRepository;
import com.securedoc.platform.application.port.out.ExportRetentionRepository;
import com.securedoc.platform.application.port.out.IntegrationConnectionRepository;
import com.securedoc.platform.application.port.out.PipelineJobRepository;
import com.securedoc.platform.application.port.out.SplitCopyRetentionPort;
import com.securedoc.platform.application.port.out.TenantLifecyclePolicyRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactStatus;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.integration.IntegrationConnection;
import com.securedoc.platform.domain.integration.IntegrationPurpose;
import com.securedoc.platform.domain.lifecycle.TenantLifecyclePolicy;
import com.securedoc.platform.domain.pipeline.PipelineJob;
import com.securedoc.platform.domain.pipeline.PipelineStage;
import com.securedoc.platform.domain.routing.DerivedDelivery;
import com.securedoc.platform.domain.routing.DerivedDeliveryStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Task 0028: retention per export destination. The Business Admin sets a period for the master destination and for
 * each split (routing) destination; the master's default is the tenant's redacted-copy retention, a split
 * destination's default is the master's. See {@link ExportCopyRetention} for what the purge does with them.
 */
public final class ExportRetentionService implements ExportCopyRetention {
    private static final Logger log = LoggerFactory.getLogger(ExportRetentionService.class);
    public static final int MIN_HOURS = 1;
    public static final int MAX_HOURS = TenantLifecyclePolicy.MAX_REDACTED_CACHE_HOURS;

    private final ExportRetentionRepository store;
    private final PipelineJobRepository jobs;
    private final DerivedDeliveryRepository deliveries;
    private final ArtifactRepository artifacts;
    private final SplitCopyRetentionPort dueCopies;
    private final IntegrationConnectionRepository connections;
    private final TenantLifecyclePolicyRepository policies;
    private final AuditService audit;

    public ExportRetentionService(ExportRetentionRepository store, PipelineJobRepository jobs,
                                  DerivedDeliveryRepository deliveries, ArtifactRepository artifacts,
                                  SplitCopyRetentionPort dueCopies, IntegrationConnectionRepository connections,
                                  TenantLifecyclePolicyRepository policies, AuditService audit) {
        this.store = Objects.requireNonNull(store);
        this.jobs = Objects.requireNonNull(jobs);
        this.deliveries = Objects.requireNonNull(deliveries);
        this.artifacts = Objects.requireNonNull(artifacts);
        this.dueCopies = Objects.requireNonNull(dueCopies);
        this.connections = Objects.requireNonNull(connections);
        this.policies = Objects.requireNonNull(policies);
        this.audit = Objects.requireNonNull(audit);
    }

    // ---------------------------------------------------------------- the periods

    private TenantLifecyclePolicy policy(TenantId tenantId) {
        return policies.findByTenantId(tenantId).orElseGet(() -> TenantLifecyclePolicy.defaults(tenantId));
    }

    @Override
    public int masterCopyHours(TenantLifecyclePolicy policy) {
        return store.masterHours(policy.tenantId()).orElse(policy.redactedCacheHours());
    }

    /** A split destination's period: its own, else the master's. */
    public int splitCopyHours(TenantId tenantId, IntegrationConnectionId destinationId) {
        Integer own = destinationId == null ? null : store.splitHours(tenantId).get(destinationId);
        return own != null ? own : masterCopyHours(policy(tenantId));
    }

    /** What the Business Admin sees: each period as set (null: the default) and as it applies. */
    public RetentionSettings settings(TenantId tenantId) {
        TenantLifecyclePolicy policy = policy(tenantId);
        Integer masterSet = store.masterHours(tenantId).orElse(null);
        int master = masterSet != null ? masterSet : policy.redactedCacheHours();
        Map<IntegrationConnectionId, Integer> splits = store.splitHours(tenantId);
        List<DestinationPeriod> destinations = new ArrayList<>();
        for (IntegrationConnection c : connections.findByTenantId(tenantId)) {
            if (c.purpose() != IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY) continue;
            Integer set = splits.get(c.id());
            destinations.add(new DestinationPeriod(c.id().value(), c.name(), set, set != null ? set : master));
        }
        return new RetentionSettings(new DestinationPeriod(null, "Master destination", masterSet, master),
                policy.redactedCacheHours(), MIN_HOURS, MAX_HOURS, destinations);
    }

    /**
     * Sets the master period ({@code masterHours} null: back to the default) and the listed split destinations' periods
     * (null: back to the master's). Destinations not listed are unchanged. Refused (LV-CFG-001) outside 1 to 72 hours
     * or for a connection that is not one of the tenant's split destinations; nothing is changed then.
     */
    public RetentionSettings update(TenantId tenantId, ActorId actorId, CorrelationId correlationId,
                                    Integer masterHours, Map<UUID, Integer> splitHours) {
        validHours(masterHours);
        Map<IntegrationConnectionId, Integer> splits = new LinkedHashMap<>();
        if (splitHours != null) {
            for (Map.Entry<UUID, Integer> e : splitHours.entrySet()) {
                if (e.getKey() == null) throw new ValidationException(LvErrorCode.CFG_001, "destinationId is required");
                IntegrationConnectionId id = IntegrationConnectionId.of(e.getKey());
                IntegrationConnection c = connections.findByTenantIdAndId(tenantId, id).orElse(null);
                if (c == null || c.purpose() != IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY) {
                    throw new ValidationException(LvErrorCode.CFG_001, "Not a split destination of this entity");
                }
                validHours(e.getValue());
                splits.put(id, e.getValue());
            }
        }
        Integer masterBefore = store.masterHours(tenantId).orElse(null);
        if (!Objects.equals(masterBefore, masterHours)) {
            store.setMaster(tenantId, masterHours, actorId);
            audit.recordSuccess(tenantId, actorId, AuditAction.EXPORT_RETENTION_PERIOD_CHANGED, "Tenant",
                    tenantId.toString(), correlationId, "Export retention changed: destination=master, hours="
                            + (masterHours == null ? "default" : masterHours));
        }
        Map<IntegrationConnectionId, Integer> before = store.splitHours(tenantId);
        for (Map.Entry<IntegrationConnectionId, Integer> e : splits.entrySet()) {
            if (Objects.equals(before.get(e.getKey()), e.getValue())) continue;
            store.setSplit(tenantId, e.getKey(), e.getValue(), actorId);
            audit.recordSuccess(tenantId, actorId, AuditAction.EXPORT_RETENTION_PERIOD_CHANGED,
                    "IntegrationConnection", e.getKey().value().toString(), correlationId,
                    "Export retention changed: destination=" + e.getKey().value() + ", hours="
                            + (e.getValue() == null ? "master" : e.getValue()));
        }
        return settings(tenantId);
    }

    private static void validHours(Integer hours) {
        if (hours != null && (hours < MIN_HOURS || hours > MAX_HOURS)) {
            throw new ValidationException(LvErrorCode.CFG_001,
                    "Retention must be between " + MIN_HOURS + " and " + MAX_HOURS + " hours");
        }
    }

    // ---------------------------------------------------------------- the purge

    private List<PipelineJob> openExportJobs(TenantId tenantId, PackageId packageId) {
        return jobs.findByTenantIdAndPackageId(tenantId, packageId).stream()
                .filter(j -> j.stage().isExport() && j.status().open()).toList();
    }

    @Override
    public boolean masterExportOpen(TenantId tenantId, PackageId packageId) {
        return openExportJobs(tenantId, packageId).stream().anyMatch(j -> j.stage() == PipelineStage.EXPORT);
    }

    @Override
    public boolean anyExportOpen(TenantId tenantId, PackageId packageId) {
        return !openExportJobs(tenantId, packageId).isEmpty();
    }

    @Override
    public Set<ArtifactId> splitCopiesToKeep(TenantId tenantId, PackageId packageId, Instant now) {
        Set<UUID> openItems = new HashSet<>();
        openExportJobs(tenantId, packageId).stream().filter(j -> j.stage() == PipelineStage.SPLIT_EXPORT)
                .forEach(j -> openItems.add(j.itemId()));
        Set<ArtifactId> keep = new HashSet<>();
        for (DerivedDelivery d : deliveries.findByTenantIdAndPackageId(tenantId, packageId)) {
            if (d.artifactId() == null) continue;
            if (openItems.contains(d.id())) {
                keep.add(d.artifactId());
            } else if (d.status() == DerivedDeliveryStatus.DELIVERED && d.deliveredAt() != null
                    && d.deliveredAt().plus(Duration.ofHours(splitCopyHours(tenantId, d.destinationConnectionId())))
                    .isAfter(now)) {
                keep.add(d.artifactId());
            }
        }
        return keep;
    }

    @Override
    public int purgeDueSplitCopies(Instant now, int limit, ActorId actorId, CorrelationId correlationId,
                                   Consumer<Artifact> delete) {
        int purged = 0;
        for (SplitCopyRetentionPort.SplitCopy copy : dueCopies.findDueSplitCopies(now, limit)) {
            try {
                boolean open = openExportJobs(copy.tenantId(), copy.packageId()).stream()
                        .anyMatch(j -> j.stage() == PipelineStage.SPLIT_EXPORT
                                && copy.derivedDeliveryId().equals(j.itemId()));
                if (open) continue;
                Artifact artifact = artifacts.findByIdAndTenantId(copy.artifactId(), copy.tenantId()).orElse(null);
                if (artifact == null || artifact.status() == ArtifactStatus.DELETED) continue;
                delete.accept(artifact);
                purged++;
                audit.recordSuccess(copy.tenantId(), actorId, AuditAction.SPLIT_COPY_PURGED, "DerivedDelivery",
                        copy.derivedDeliveryId().toString(), copy.packageId(), correlationId,
                        "Split copy purged after its retention from receipt: destination="
                                + (copy.destinationId() == null ? "none" : copy.destinationId().value())
                                + ", hours=" + splitCopyHours(copy.tenantId(), copy.destinationId()));
            } catch (RuntimeException e) {
                log.warn("SPLIT_COPY_PURGE_FAILED derivedDelivery={} type={}", copy.derivedDeliveryId(),
                        e.getClass().getSimpleName());
                audit.recordFailure(copy.tenantId(), actorId, AuditAction.SPLIT_COPY_PURGED, "DerivedDelivery",
                        copy.derivedDeliveryId().toString(), copy.packageId(), correlationId,
                        "Split copy purge failed: type=" + e.getClass().getSimpleName());
            }
        }
        return purged;
    }

    // ---------------------------------------------------------------- types

    /** {@code setHours} null: the default applies; {@code hours} is what applies. */
    public record DestinationPeriod(UUID destinationId, String name, Integer setHours, int hours) { }

    public record RetentionSettings(DestinationPeriod master, int masterDefaultHours, int minHours, int maxHours,
                                    List<DestinationPeriod> splitDestinations) { }
}
