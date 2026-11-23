package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.domain.business.ReviewMode;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.persistence.entity.TenantRedactionPolicyEntity;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataTenantRedactionPolicyRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Optional;

/**
 * The V1 four kinds are columns of tenant_redaction_policies (unchanged); the opt-in kinds of task 0043 are rows of
 * tenant_redaction_kinds (migration 047), where a missing row means off. "Searchable redacted pages" is the column
 * searchable_redacted_pages (migration 048, task 0047; on by default).
 */
@Component
public class JpaTenantRedactionPolicyRepositoryAdapter implements TenantRedactionPolicyRepository {
    private final SpringDataTenantRedactionPolicyRepository repository;
    private final JdbcTenantRedactionKindStore kindStore;

    public JpaTenantRedactionPolicyRepositoryAdapter(SpringDataTenantRedactionPolicyRepository repository,
                                                     JdbcTenantRedactionKindStore kindStore) {
        this.repository = repository;
        this.kindStore = kindStore;
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
        entity.setSsnEnabled(policy.enabledEntityTypes().contains(EntityType.SSN));
        entity.setMaskSsnLastFour(policy.maskSsnLastFour());
        entity.setDateOfBirthEnabled(policy.enabledEntityTypes().contains(EntityType.DATE_OF_BIRTH));
        entity.setCreditCardNumberEnabled(policy.enabledEntityTypes().contains(EntityType.CREDIT_CARD_NUMBER));
        entity.setCreditScoreEnabled(policy.enabledEntityTypes().contains(EntityType.CREDIT_SCORE));
        entity.setSearchableRedactedPages(policy.searchableRedactedPages());
        entity.setUpdatedAt(policy.updatedAt());
        repository.save(entity);
        // Same transaction: the policy row and the kind rows change together or not at all.
        kindStore.save(policy.tenantId(), policy.enabledEntityTypes(), policy.updatedAt());
    }

    private TenantRedactionPolicy toDomain(TenantRedactionPolicyEntity entity) {
        EnumSet<EntityType> enabled = EnumSet.noneOf(EntityType.class);
        if (entity.isSsnEnabled()) enabled.add(EntityType.SSN);
        if (entity.isDateOfBirthEnabled()) enabled.add(EntityType.DATE_OF_BIRTH);
        if (entity.isCreditCardNumberEnabled()) enabled.add(EntityType.CREDIT_CARD_NUMBER);
        if (entity.isCreditScoreEnabled()) enabled.add(EntityType.CREDIT_SCORE);
        TenantId tenantId = TenantId.of(entity.getTenantId());
        enabled.addAll(kindStore.enabledKinds(tenantId));
        return new TenantRedactionPolicy(
                tenantId, enabled, entity.getAutoRedactThreshold(),
                ReviewMode.valueOf(entity.getReviewMode()), entity.isMaskSsnLastFour(), entity.getUpdatedAt(),
                entity.isSearchableRedactedPages());
    }
}
