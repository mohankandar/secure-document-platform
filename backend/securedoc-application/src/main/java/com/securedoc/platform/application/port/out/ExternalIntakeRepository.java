package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.intake.ExternalIntake;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ExternalIntakeRepository {
    Optional<ExternalIntake> findByTenantIdAndPackageId(TenantId tenantId, PackageId packageId);
    Optional<ExternalIntake> findByTenantIdAndPackageIdForUpdate(TenantId tenantId, PackageId packageId);
    Optional<ExternalIntake> findBySourceIdentity(TenantId tenantId, IntegrationConnectionId connectionId,
                                                   String externalSourcePackageId, String sourceRevision);
    List<ExternalIntake> findAwaitingExpiredBefore(Instant now, int limit);
    List<ExternalIntake> findExpiredCleanupPending(int limit);
    void save(ExternalIntake intake);
}
