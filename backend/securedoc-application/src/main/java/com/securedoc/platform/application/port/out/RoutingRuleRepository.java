package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.routing.RoutingMarkerType;
import com.securedoc.platform.domain.routing.RoutingRule;
import com.securedoc.platform.domain.routing.RoutingScanRegion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoutingRuleRepository {
    List<RoutingRule> findByTenantId(TenantId tenantId);
    List<RoutingRule> findEnabledByTenantId(TenantId tenantId);
    Optional<RoutingRule> findByTenantIdAndId(TenantId tenantId, UUID id);
    boolean existsDuplicate(TenantId tenantId, RoutingMarkerType type, String markerValue, RoutingScanRegion region, UUID excludeId);
    void save(RoutingRule rule);
    void delete(TenantId tenantId, UUID id);
}
