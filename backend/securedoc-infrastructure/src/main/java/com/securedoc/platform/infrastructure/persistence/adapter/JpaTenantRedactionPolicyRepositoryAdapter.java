package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.domain.business.ReviewMode;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.persistence.entity.TenantRedactionPolicyEntity;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataTenantRedactionPolicyRepository;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Optional;

@Component
public class JpaTenantRedactionPolicyRepositoryAdapter implements TenantRedactionPolicyRepository {
    private final SpringDataTenantRedactionPolicyRepository repository;

    public JpaTenantRedactionPolicyRepositoryAdapter(SpringDataTenantRedactionPolicyRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<TenantRedactionPolicy> findByTenantId(TenantId tenantId) {
        return repository.findById(tenantId.value()).map(this::toDomain);
    }

    @Override
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
        entity.setUpdatedAt(policy.updatedAt());
        repository.save(entity);
    }

    private TenantRedactionPolicy toDomain(TenantRedactionPolicyEntity entity) {
        EnumSet<EntityType> enabled = EnumSet.noneOf(EntityType.class);
        if (entity.isSsnEnabled()) enabled.add(EntityType.SSN);
        if (entity.isDateOfBirthEnabled()) enabled.add(EntityType.DATE_OF_BIRTH);
        if (entity.isCreditCardNumberEnabled()) enabled.add(EntityType.CREDIT_CARD_NUMBER);
        if (entity.isCreditScoreEnabled()) enabled.add(EntityType.CREDIT_SCORE);
        return new TenantRedactionPolicy(
                TenantId.of(entity.getTenantId()), enabled, entity.getAutoRedactThreshold(),
                ReviewMode.valueOf(entity.getReviewMode()), entity.isMaskSsnLastFour(), entity.getUpdatedAt());
    }
}
