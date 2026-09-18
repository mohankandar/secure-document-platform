package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.port.out.PackageProcessingIntelligenceSnapshotRepository;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.intelligence.DocumentIntelligenceMode;
import com.securedoc.platform.domain.intelligence.PackageProcessingIntelligenceSnapshot;
import com.securedoc.platform.infrastructure.persistence.entity.PackageProcessingIntelligenceSnapshotEntity;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataPackageProcessingIntelligenceSnapshotRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class JpaPackageProcessingIntelligenceSnapshotRepositoryAdapter implements PackageProcessingIntelligenceSnapshotRepository {
    private final SpringDataPackageProcessingIntelligenceSnapshotRepository repository;

    public JpaPackageProcessingIntelligenceSnapshotRepositoryAdapter(SpringDataPackageProcessingIntelligenceSnapshotRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<PackageProcessingIntelligenceSnapshot> findByTenantIdAndPackageIdAndProcessingCycle(
            TenantId tenantId, PackageId packageId, int processingCycle) {
        return repository.findByTenantIdAndPackageIdAndProcessingCycle(tenantId.value(), packageId.value(), processingCycle)
                .map(this::toDomain);
    }

    @Override
    public Optional<PackageProcessingIntelligenceSnapshot> findLatestByTenantIdAndPackageId(
            TenantId tenantId, PackageId packageId) {
        return repository.findFirstByTenantIdAndPackageIdOrderByProcessingCycleDesc(tenantId.value(), packageId.value())
                .map(this::toDomain);
    }

    @Override
    public PackageProcessingIntelligenceSnapshot saveIfAbsent(PackageProcessingIntelligenceSnapshot snapshot) {
        Optional<PackageProcessingIntelligenceSnapshot> existing = findByTenantIdAndPackageIdAndProcessingCycle(
                snapshot.tenantId(), snapshot.packageId(), snapshot.processingCycle());
        if (existing.isPresent()) return existing.get();
        try {
            repository.saveAndFlush(toEntity(snapshot));
            return snapshot;
        } catch (DataIntegrityViolationException concurrentInsert) {
            return findByTenantIdAndPackageIdAndProcessingCycle(snapshot.tenantId(), snapshot.packageId(), snapshot.processingCycle())
                    .orElseThrow(() -> concurrentInsert);
        }
    }

    private PackageProcessingIntelligenceSnapshotEntity toEntity(PackageProcessingIntelligenceSnapshot snapshot) {
        PackageProcessingIntelligenceSnapshotEntity entity = new PackageProcessingIntelligenceSnapshotEntity();
        entity.setId(snapshot.id());
        entity.setTenantId(snapshot.tenantId().value());
        entity.setPackageId(snapshot.packageId().value());
        entity.setProcessingCycle(snapshot.processingCycle());
        entity.setRequestedMode(snapshot.requestedMode().name());
        entity.setEffectiveMode(snapshot.effectiveMode().name());
        entity.setBundleVersion(snapshot.bundleVersion());
        entity.setPolicyConfigurationVersion(snapshot.policyConfigurationVersion());
        entity.setIntelligenceExecutionAllowed(snapshot.intelligenceExecutionAllowed());
        entity.setDecisionInfluenceAllowed(snapshot.decisionInfluenceAllowed());
        entity.setBaselineDiscoveryRequired(snapshot.baselineDiscoveryRequired());
        entity.setFallbackToBaseline(snapshot.fallbackToBaseline());
        entity.setCapturedAt(snapshot.capturedAt());
        return entity;
    }

    private PackageProcessingIntelligenceSnapshot toDomain(PackageProcessingIntelligenceSnapshotEntity entity) {
        return new PackageProcessingIntelligenceSnapshot(entity.getId(), TenantId.of(entity.getTenantId()),
                PackageId.of(entity.getPackageId()), entity.getProcessingCycle(),
                DocumentIntelligenceMode.valueOf(entity.getRequestedMode()),
                DocumentIntelligenceMode.valueOf(entity.getEffectiveMode()), entity.getBundleVersion(),
                entity.getPolicyConfigurationVersion(), entity.isIntelligenceExecutionAllowed(),
                entity.isDecisionInfluenceAllowed(), entity.isBaselineDiscoveryRequired(),
                entity.isFallbackToBaseline(), entity.getCapturedAt());
    }
}
