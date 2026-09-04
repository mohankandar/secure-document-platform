package com.securedoc.platform.infrastructure.persistence.repository;
import com.securedoc.platform.infrastructure.persistence.entity.DerivedDeliveryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface SpringDataDerivedDeliveryRepository extends JpaRepository<DerivedDeliveryEntity,UUID>{
    List<DerivedDeliveryEntity> findByTenantIdAndPackageIdOrderByCreatedAtAsc(UUID tenantId,UUID packageId);
    Optional<DerivedDeliveryEntity> findByTenantIdAndId(UUID tenantId,UUID id);
    List<DerivedDeliveryEntity> findByTenantIdAndStatusInOrderByUpdatedAtDesc(UUID tenantId,Collection<String> statuses);
    Optional<DerivedDeliveryEntity> findByTenantIdAndPackageIdAndExportCycleAndSourceDocumentIdAndRoutingRuleId(UUID tenantId,UUID packageId,int exportCycle,UUID sourceDocumentId,UUID routingRuleId);
}
