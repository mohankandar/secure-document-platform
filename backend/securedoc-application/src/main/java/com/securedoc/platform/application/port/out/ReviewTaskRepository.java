package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.ReviewTaskId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.review.ReviewStatus;
import com.securedoc.platform.domain.review.ReviewTask;

import java.util.List;
import java.util.Optional;

/**
 * Outbound port for persisting and querying review tasks.
 * All query methods are tenant-scoped.
 */
public interface ReviewTaskRepository {

    void save(ReviewTask task);

    Optional<ReviewTask> findByIdAndTenantId(ReviewTaskId id, TenantId tenantId);

    List<ReviewTask> findByPackageIdAndTenantId(PackageId packageId, TenantId tenantId);

    List<ReviewTask> findByStatusAndTenantId(ReviewStatus status, TenantId tenantId);

    Optional<ReviewTask> findOpenByPackageIdAndTenantId(PackageId packageId, TenantId tenantId);
}
