package com.securedoc.platform.application.licensing;

import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.licensing.TenantLicenseRecord;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for signed tenant-license history and current assignment. */
public interface TenantLicenseRepository {
    TenantLicenseRecord save(TenantLicenseRecord record);
    void activate(TenantId tenantId, UUID licenseRecordId, UUID actorId);
    Optional<TenantLicenseRecord> findActive(TenantId tenantId);
    List<TenantLicenseRecord> findHistory(TenantId tenantId);
}
