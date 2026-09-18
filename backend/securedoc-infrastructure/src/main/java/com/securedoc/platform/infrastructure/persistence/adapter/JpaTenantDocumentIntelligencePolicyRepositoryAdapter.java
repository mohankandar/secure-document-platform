package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.port.out.TenantDocumentIntelligencePolicyRepository;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.intelligence.DocumentIntelligenceMode;
import com.securedoc.platform.domain.intelligence.TenantDocumentIntelligencePolicy;
import com.securedoc.platform.infrastructure.persistence.entity.TenantDocumentIntelligencePolicyEntity;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataTenantDocumentIntelligencePolicyRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class JpaTenantDocumentIntelligencePolicyRepositoryAdapter implements TenantDocumentIntelligencePolicyRepository {
    private final SpringDataTenantDocumentIntelligencePolicyRepository repository;

    public JpaTenantDocumentIntelligencePolicyRepositoryAdapter(SpringDataTenantDocumentIntelligencePolicyRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<TenantDocumentIntelligencePolicy> findByTenantId(TenantId tenantId) {
        return repository.findById(tenantId.value()).map(this::toDomain);
    }

    @Override
    public void save(TenantDocumentIntelligencePolicy policy) {
        TenantDocumentIntelligencePolicyEntity entity = repository.findById(policy.tenantId().value())
                .orElseGet(TenantDocumentIntelligencePolicyEntity::new);
        entity.setTenantId(policy.tenantId().value());
        entity.setMode(policy.mode().name());
        entity.setActiveBundleVersion(policy.activeBundleVersion());
        entity.setLastKnownGoodBundleVersion(policy.lastKnownGoodBundleVersion());
        entity.setConfigurationVersion(policy.configurationVersion());
        entity.setUpdatedAt(policy.updatedAt());
        repository.save(entity);
    }

    private TenantDocumentIntelligencePolicy toDomain(TenantDocumentIntelligencePolicyEntity entity) {
        return new TenantDocumentIntelligencePolicy(TenantId.of(entity.getTenantId()),
                DocumentIntelligenceMode.valueOf(entity.getMode()), entity.getActiveBundleVersion(),
                entity.getLastKnownGoodBundleVersion(), entity.getConfigurationVersion(), entity.getUpdatedAt());
    }
}
