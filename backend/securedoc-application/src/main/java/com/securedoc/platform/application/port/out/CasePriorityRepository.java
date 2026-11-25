package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.PipelinePriority;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/** Task 0073: the priority of cases and the default priority of sources. */
public interface CasePriorityRepository {

    /** No row is kept for NORMAL on a new case. The source is kept with BULK (task 0079): its window and progress. */
    void saveNew(TenantId tenantId, PackageId packageId, PipelinePriority priority, String sourceKey);

    /**
     * Changes the case's priority and the priority of its jobs that wait, at once: a job queued at the same moment has
     * the new priority too. A running job finishes as it is. Must be called inside a transaction.
     *
     * @return the priority before
     */
    PipelinePriority change(TenantId tenantId, PackageId packageId, PipelinePriority priority);

    /** Every case asked for; NORMAL where none is stored. */
    Map<PackageId, PipelinePriority> find(TenantId tenantId, Collection<PackageId> packageIds);

    Optional<PipelinePriority> sourceDefault(TenantId tenantId, String sourceKey);

    Map<String, PipelinePriority> sourceDefaults(TenantId tenantId);

    void saveSourceDefault(TenantId tenantId, String sourceKey, PipelinePriority priority);
}
