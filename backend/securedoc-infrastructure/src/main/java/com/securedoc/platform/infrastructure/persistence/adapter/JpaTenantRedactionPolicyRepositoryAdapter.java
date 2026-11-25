package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.domain.business.ReviewMode;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.persistence.entity.TenantRedactionPolicyEntity;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataTenantRedactionPolicyRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The policy row holds the review settings and whether the locked items are on; its kinds, styles and regulation packs
 * are rows of tenant_redaction_catalog and tenant_regulation_packs (migration 070, task 0062).
 */
@Component
public class JpaTenantRedactionPolicyRepositoryAdapter implements TenantRedactionPolicyRepository {
    private final SpringDataTenantRedactionPolicyRepository repository;
    private final JdbcRedactionCatalogStore catalogStore;

    public JpaTenantRedactionPolicyRepositoryAdapter(SpringDataTenantRedactionPolicyRepository repository,
                                                     JdbcRedactionCatalogStore catalogStore) {
        this.repository = repository;
        this.catalogStore = catalogStore;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TenantRedactionPolicy> findByTenantId(TenantId tenantId) {
        return repository.findById(tenantId.value()).map(this::toDomain);
    }

    @Override
    @Transactional
    public void save(TenantRedactionPolicy policy) {
        TenantRedactionPolicyEntity entity = repository.findById(policy.tenantId().value())
                .orElseGet(TenantRedactionPolicyEntity::new);
        entity.setTenantId(policy.tenantId().value());
        entity.setAutoRedactThreshold(policy.autoRedactThreshold());
        entity.setReviewMode(policy.reviewMode().name());
        entity.setMaskSsnLastFour(policy.maskSsnLastFour());
        entity.setSearchableRedactedPages(policy.searchableRedactedPages());
        entity.setPageBatchSize(policy.pageBatches().size());
        entity.setPageBatchOverlap(policy.pageBatches().overlap());
        entity.setVisionEnabled(policy.vision().enabled());
        entity.setVisionOcrThreshold(policy.vision().ocrThreshold());
        entity.setSecondCheckEnabled(policy.secondCheck());
        entity.setLockedItemsOn(policy.lockedItemsOn());
        entity.setUpdatedAt(policy.updatedAt());
        repository.save(entity);
        // The catalog rows refer to the entity (tenants), not to this row. Same transaction: together or not at all.
        catalogStore.save(policy);
    }

    private TenantRedactionPolicy toDomain(TenantRedactionPolicyEntity entity) {
        TenantId tenantId = TenantId.of(entity.getTenantId());
        JdbcRedactionCatalogStore.Stored stored = catalogStore.load(tenantId);
        return new TenantRedactionPolicy(
                tenantId, stored.enabled(), entity.getAutoRedactThreshold(),
                ReviewMode.valueOf(entity.getReviewMode()), entity.isMaskSsnLastFour(), entity.getUpdatedAt(),
                entity.isSearchableRedactedPages(),
                new com.securedoc.platform.domain.business.PageBatchSettings(
                        entity.getPageBatchSize(), entity.getPageBatchOverlap()),
                stored.packs(), stored.keepLastFour(), entity.isLockedItemsOn(),
                new com.securedoc.platform.domain.business.VisionSettings(
                        entity.isVisionEnabled(), entity.getVisionOcrThreshold()),
                entity.isSecondCheckEnabled());
    }
}
