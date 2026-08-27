package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.port.out.AccessRequestRepository;
import com.securedoc.platform.domain.identity.AccessRequestId;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.identity.VaultEntryId;
import com.securedoc.platform.domain.vault.AccessRequest;
import com.securedoc.platform.domain.vault.AccessRequestStatus;
import com.securedoc.platform.infrastructure.persistence.entity.AccessRequestEntity;
import com.securedoc.platform.infrastructure.persistence.mapper.AccessRequestMapper;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataAccessRequestRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class JpaAccessRequestRepositoryAdapter implements AccessRequestRepository {

    private final SpringDataAccessRequestRepository jpaRepo;

    public JpaAccessRequestRepositoryAdapter(SpringDataAccessRequestRepository jpaRepo) {
        this.jpaRepo = jpaRepo;
    }

    @Override
    public void save(AccessRequest request) {
        Optional<AccessRequestEntity> existing = jpaRepo.findByIdAndTenantId(
                request.id().value(), request.tenantId().value());
        if (existing.isPresent()) {
            AccessRequestMapper.updateEntity(request, existing.get());
            jpaRepo.save(existing.get());
        } else {
            jpaRepo.save(AccessRequestMapper.toNewEntity(request));
        }
    }

    @Override
    public Optional<AccessRequest> findByIdAndTenantId(AccessRequestId id, TenantId tenantId) {
        return jpaRepo.findByIdAndTenantId(id.value(), tenantId.value())
                .map(AccessRequestMapper::toDomain);
    }

    @Override
    public List<AccessRequest> findByTenantId(TenantId tenantId) {
        return jpaRepo.findByTenantId(tenantId.value())
                .stream()
                .map(AccessRequestMapper::toDomain)
                .toList();
    }

    @Override
    public List<AccessRequest> findByVaultEntryIdAndTenantId(VaultEntryId vaultEntryId, TenantId tenantId) {
        return jpaRepo.findByVaultEntryIdAndTenantId(vaultEntryId.value(), tenantId.value())
                .stream()
                .map(AccessRequestMapper::toDomain)
                .toList();
    }

    @Override
    public List<AccessRequest> findApprovedByArtifactIdAndRequesterAndTenantId(
            ArtifactId artifactId, ActorId requesterId, TenantId tenantId) {
        return jpaRepo.findByArtifactIdAndRequestedByAndStatusAndTenantId(
                        artifactId.value(), requesterId.value(),
                        AccessRequestStatus.APPROVED.name(), tenantId.value())
                .stream()
                .map(AccessRequestMapper::toDomain)
                .toList();
    }

    @Override
    public List<AccessRequest> findByStatusAndTenantId(AccessRequestStatus status, TenantId tenantId) {
        return jpaRepo.findByStatusAndTenantId(status.name(), tenantId.value())
                .stream()
                .map(AccessRequestMapper::toDomain)
                .toList();
    }
}
