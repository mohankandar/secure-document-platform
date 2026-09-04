package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.delivery.DeliveryAttempt;
import com.securedoc.platform.domain.delivery.DeliverySubmission;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Optional;

public interface DeliverySubmissionRepository {
    Optional<DeliverySubmission> findByTenantIdAndPackageIdAndExportCycle(TenantId tenantId, PackageId packageId, int exportCycle);
    void save(DeliverySubmission submission);
    void saveAttempt(DeliveryAttempt attempt);
}
