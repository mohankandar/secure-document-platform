package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.port.out.TenantLifecyclePolicyRepository;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.lifecycle.TenantLifecyclePolicy;
import com.securedoc.platform.infrastructure.persistence.entity.TenantLifecyclePolicyEntity;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataTenantLifecyclePolicyRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class JpaTenantLifecyclePolicyRepositoryAdapter implements TenantLifecyclePolicyRepository {
    private final SpringDataTenantLifecyclePolicyRepository repository;

    public JpaTenantLifecyclePolicyRepositoryAdapter(SpringDataTenantLifecyclePolicyRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<TenantLifecyclePolicy> findByTenantId(TenantId tenantId) {
        return repository.findById(tenantId.value()).map(this::toDomain);
    }

    @Override
    public void save(TenantLifecyclePolicy policy) {
        TenantLifecyclePolicyEntity entity = repository.findById(policy.tenantId().value())
                .orElseGet(TenantLifecyclePolicyEntity::new);
        entity.setTenantId(policy.tenantId().value());
        entity.setOriginalRetentionHours(policy.originalRetentionHours());
        entity.setRedactedCacheHours(policy.redactedCacheHours());
        entity.setNeverExportedHoldHours(policy.neverExportedHoldHours());
        entity.setFinalApprovalRequired(policy.finalApprovalRequired());
        entity.setUpdatedAt(policy.updatedAt());
        repository.save(entity);
    }

    private TenantLifecyclePolicy toDomain(TenantLifecyclePolicyEntity entity) {
        return new TenantLifecyclePolicy(
                TenantId.of(entity.getTenantId()), entity.getOriginalRetentionHours(), entity.getRedactedCacheHours(),
                entity.getNeverExportedHoldHours(), entity.isFinalApprovalRequired(), entity.getUpdatedAt());
    }
}
