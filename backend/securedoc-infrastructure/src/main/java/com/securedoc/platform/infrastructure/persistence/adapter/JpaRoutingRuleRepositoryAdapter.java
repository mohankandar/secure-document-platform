package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.port.out.RoutingRuleRepository;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.routing.*;
import com.securedoc.platform.infrastructure.persistence.entity.RoutingRuleEntity;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataRoutingRuleRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Component
public class JpaRoutingRuleRepositoryAdapter implements RoutingRuleRepository {
    private final SpringDataRoutingRuleRepository repo; public JpaRoutingRuleRepositoryAdapter(SpringDataRoutingRuleRepository repo){this.repo=repo;}
    public List<RoutingRule> findByTenantId(TenantId t){return repo.findByTenantIdOrderByDestinationLabelAsc(t.value()).stream().map(this::toDomain).toList();}
    public List<RoutingRule> findEnabledByTenantId(TenantId t){return repo.findByTenantIdAndEnabledTrueOrderByDestinationLabelAsc(t.value()).stream().map(this::toDomain).toList();}
    public Optional<RoutingRule> findByTenantIdAndId(TenantId t,UUID id){return repo.findByTenantIdAndId(t.value(),id).map(this::toDomain);}
    public boolean existsDuplicate(TenantId t,RoutingMarkerType type,String markerValue,RoutingScanRegion region,UUID exclude){return exclude==null?repo.existsByTenantIdAndMarkerTypeAndMarkerValueAndScanRegion(t.value(),type.name(),markerValue.trim(),region.name()):repo.existsByTenantIdAndMarkerTypeAndMarkerValueAndScanRegionAndIdNot(t.value(),type.name(),markerValue.trim(),region.name(),exclude);}
    public void save(RoutingRule r){RoutingRuleEntity e=repo.findById(r.id()).orElseGet(RoutingRuleEntity::new);e.setId(r.id());e.setTenantId(r.tenantId().value());e.setDestinationLabel(r.destinationLabel());e.setMarkerType(r.markerType().name());e.setMarkerValue(r.markerValue());e.setScanRegion(r.scanRegion().name());e.setDestinationConnectionId(r.destinationConnectionId().value());e.setEnabled(r.enabled());e.setCreatedAt(r.createdAt());e.setUpdatedAt(r.updatedAt());repo.save(e);}
    @Transactional public void delete(TenantId t,UUID id){repo.deleteByTenantIdAndId(t.value(),id);}
    private RoutingRule toDomain(RoutingRuleEntity e){return RoutingRule.reconstitute(e.getId(),TenantId.of(e.getTenantId()),e.getDestinationLabel(),RoutingMarkerType.valueOf(e.getMarkerType()),e.getMarkerValue(),RoutingScanRegion.valueOf(e.getScanRegion()),IntegrationConnectionId.of(e.getDestinationConnectionId()),e.isEnabled(),e.getCreatedAt(),e.getUpdatedAt());}
}
