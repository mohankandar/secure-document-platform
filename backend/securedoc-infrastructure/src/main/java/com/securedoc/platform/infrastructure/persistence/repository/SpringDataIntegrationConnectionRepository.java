package com.securedoc.platform.infrastructure.persistence.repository;

import com.securedoc.platform.infrastructure.persistence.entity.IntegrationConnectionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataIntegrationConnectionRepository extends JpaRepository<IntegrationConnectionEntity, UUID> {
    List<IntegrationConnectionEntity> findByTenantIdOrderByNameAsc(UUID tenantId);
    Optional<IntegrationConnectionEntity> findByIdAndTenantId(UUID id, UUID tenantId);
    Optional<IntegrationConnectionEntity> findByTenantIdAndClientPrincipalAndProviderTypeAndEnabledTrue(
            UUID tenantId, String clientPrincipal, String providerType);
    List<IntegrationConnectionEntity> findByPurposeAndDirectionAndProviderTypeAndEnabledTrueOrderByNameAsc(
            String purpose, String direction, String providerType);
    List<IntegrationConnectionEntity> findByTenantIdAndPurposeAndDirectionAndProviderTypeAndEnabledTrueOrderByNameAsc(
            UUID tenantId, String purpose, String direction, String providerType);
    boolean existsByTenantIdAndNameIgnoreCase(UUID tenantId, String name);
    boolean existsByTenantIdAndNameIgnoreCaseAndIdNot(UUID tenantId, String name, UUID id);
    boolean existsByTenantIdAndClientPrincipal(UUID tenantId, String clientPrincipal);
    boolean existsByTenantIdAndClientPrincipalAndIdNot(UUID tenantId, String clientPrincipal, UUID id);
}
