package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.routing.DerivedDelivery;
import com.securedoc.platform.domain.routing.DerivedDeliveryAttempt;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DerivedDeliveryRepository {
    List<DerivedDelivery> findByTenantIdAndPackageId(TenantId tenantId, PackageId packageId);
    Optional<DerivedDelivery> findByTenantIdAndId(TenantId tenantId, UUID id);
    List<DerivedDelivery> findAttentionByTenantId(TenantId tenantId);
    Optional<DerivedDelivery> findByTenantIdAndPackageIdAndExportCycleAndSourceDocumentIdAndRoutingRuleId(
            TenantId tenantId, PackageId packageId, int exportCycle, DocumentId documentId, UUID routingRuleId);
    void save(DerivedDelivery delivery);
    void saveAttempt(DerivedDeliveryAttempt attempt);
}
