package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.integration.IntegrationConnection;

import java.util.List;
import java.util.Optional;

public interface IntegrationConnectionRepository {
    List<IntegrationConnection> findByTenantId(TenantId tenantId);
    Optional<IntegrationConnection> findByTenantIdAndId(TenantId tenantId, IntegrationConnectionId id);
    Optional<IntegrationConnection> findEnabledPushSourceByTenantIdAndClientPrincipal(TenantId tenantId, String clientPrincipal);
    default Optional<IntegrationConnection> findEnabledSdvDeliveryByTenantId(TenantId tenantId) { return Optional.empty(); }
    default List<IntegrationConnection> findEnabledPollingSources() { return List.of(); }
    boolean existsByTenantIdAndNameIgnoreCase(TenantId tenantId, String name, IntegrationConnectionId excludeId);
    boolean existsByTenantIdAndClientPrincipal(TenantId tenantId, String clientPrincipal, IntegrationConnectionId excludeId);
    void save(IntegrationConnection connection);
}
