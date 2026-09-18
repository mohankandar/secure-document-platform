package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.port.out.TenantRepository;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.tenant.Tenant;
import com.securedoc.platform.infrastructure.persistence.entity.TenantCapabilityEntity;
import com.securedoc.platform.infrastructure.persistence.entity.TenantEntity;
import com.securedoc.platform.infrastructure.persistence.mapper.TenantMapper;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataTenantCapabilityRepository;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataTenantRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class JpaTenantRepositoryAdapter implements TenantRepository {

    private final SpringDataTenantRepository tenantRepo;
    private final SpringDataTenantCapabilityRepository capabilityRepo;

    public JpaTenantRepositoryAdapter(SpringDataTenantRepository tenantRepo,
                                       SpringDataTenantCapabilityRepository capabilityRepo) {
        this.tenantRepo = tenantRepo;
        this.capabilityRepo = capabilityRepo;
    }

    @Override
    public Optional<Tenant> findById(TenantId tenantId) {
        return tenantRepo.findById(tenantId.value())
                .map(entity -> toDomainWithCapabilities(entity));
    }

    @Override
    public Optional<Tenant> findBySlug(String slug) {
        return tenantRepo.findBySlug(slug)
                .map(entity -> toDomainWithCapabilities(entity));
    }

    @Override
    public List<Tenant> findAll() {
        return tenantRepo.findAll().stream()
                .map(this::toDomainWithCapabilities)
                .toList();
    }

    private Tenant toDomainWithCapabilities(TenantEntity entity) {
        List<TenantCapabilityEntity> capabilities = capabilityRepo.findByTenantId(entity.getId());
        return TenantMapper.toDomain(entity, capabilities);
    }
}
