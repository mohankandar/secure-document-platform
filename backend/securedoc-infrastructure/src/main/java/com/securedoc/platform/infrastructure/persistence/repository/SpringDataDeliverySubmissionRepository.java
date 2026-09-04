package com.securedoc.platform.infrastructure.persistence.repository;
import com.securedoc.platform.infrastructure.persistence.entity.DeliverySubmissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional; import java.util.UUID;
public interface SpringDataDeliverySubmissionRepository extends JpaRepository<DeliverySubmissionEntity,UUID>{
    Optional<DeliverySubmissionEntity> findByTenantIdAndPackageIdAndExportCycle(UUID tenantId, UUID packageId, int exportCycle);
}
