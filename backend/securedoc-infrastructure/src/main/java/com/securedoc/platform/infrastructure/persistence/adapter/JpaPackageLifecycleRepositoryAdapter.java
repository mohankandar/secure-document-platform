package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.port.out.PackageLifecycleRepository;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.lifecycle.PackageLifecycle;
import com.securedoc.platform.infrastructure.persistence.entity.PackageLifecycleEntity;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataPackageLifecycleRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class JpaPackageLifecycleRepositoryAdapter implements PackageLifecycleRepository {
    private final SpringDataPackageLifecycleRepository repository;

    public JpaPackageLifecycleRepositoryAdapter(SpringDataPackageLifecycleRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<PackageLifecycle> findByPackageIdAndTenantId(PackageId packageId, TenantId tenantId) {
        return repository.findByPackageIdAndTenantId(packageId.value(), tenantId.value()).map(this::toDomain);
    }

    @Override
    public void save(PackageLifecycle lifecycle) {
        PackageLifecycleEntity entity = repository.findByPackageIdAndTenantId(
                lifecycle.packageId().value(), lifecycle.tenantId().value()).orElseGet(PackageLifecycleEntity::new);
        entity.setPackageId(lifecycle.packageId().value());
        entity.setTenantId(lifecycle.tenantId().value());
        entity.setSuccessfulExportCycles(lifecycle.successfulExportCycles());
        entity.setLastExportedAt(lifecycle.lastExportedAt().orElse(null));
        entity.setOriginalDeleteAt(lifecycle.originalDeleteAt().orElse(null));
        entity.setRedactedDeleteAt(lifecycle.redactedDeleteAt().orElse(null));
        entity.setTechnicalHoldUntil(lifecycle.technicalHoldUntil().orElse(null));
        entity.setRedactedPurgedAt(lifecycle.redactedPurgedAt().orElse(null));
        entity.setOriginalPurgedAt(lifecycle.originalPurgedAt().orElse(null));
        entity.setPurgedAt(lifecycle.purgedAt().orElse(null));
        entity.setFinalApprovalGranted(lifecycle.finalApprovalGranted());
        entity.setUpdatedAt(lifecycle.updatedAt());
        repository.save(entity);
    }

    @Override
    public List<PackageLifecycle> findDueRedactedPurge(Instant now, int limit) {
        return repository.findDueRedacted(now, PageRequest.of(0, limit)).stream().map(this::toDomain).toList();
    }

    @Override
    public List<PackageLifecycle> findDueFinalPurge(Instant now, int limit) {
        return repository.findDueFinal(now, PageRequest.of(0, limit)).stream().map(this::toDomain).toList();
    }

    private PackageLifecycle toDomain(PackageLifecycleEntity entity) {
        return PackageLifecycle.reconstitute(
                PackageId.of(entity.getPackageId()), TenantId.of(entity.getTenantId()),
                entity.getSuccessfulExportCycles(), entity.getLastExportedAt(), entity.getOriginalDeleteAt(),
                entity.getRedactedDeleteAt(), entity.getTechnicalHoldUntil(), entity.getRedactedPurgedAt(),
                entity.getOriginalPurgedAt(), entity.getPurgedAt(), entity.isFinalApprovalGranted(), entity.getUpdatedAt());
    }
}
