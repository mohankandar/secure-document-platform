package com.securedoc.platform.infrastructure.persistence.repository;
import com.securedoc.platform.infrastructure.persistence.entity.RoutingRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface SpringDataRoutingRuleRepository extends JpaRepository<RoutingRuleEntity,UUID>{
    List<RoutingRuleEntity> findByTenantIdOrderByDestinationLabelAsc(UUID tenantId);
    List<RoutingRuleEntity> findByTenantIdAndEnabledTrueOrderByDestinationLabelAsc(UUID tenantId);
    Optional<RoutingRuleEntity> findByTenantIdAndId(UUID tenantId,UUID id);
    boolean existsByTenantIdAndMarkerTypeAndMarkerValueAndScanRegion(UUID tenantId,String markerType,String markerValue,String scanRegion);
    boolean existsByTenantIdAndMarkerTypeAndMarkerValueAndScanRegionAndIdNot(UUID tenantId,String markerType,String markerValue,String scanRegion,UUID id);
    void deleteByTenantIdAndId(UUID tenantId,UUID id);
}
