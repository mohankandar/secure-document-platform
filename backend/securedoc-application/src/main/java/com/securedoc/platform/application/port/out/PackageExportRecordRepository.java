package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.lifecycle.PackageExportRecord;

import java.util.List;

public interface PackageExportRecordRepository {
    void save(PackageExportRecord record);
    List<PackageExportRecord> findByPackageIdAndTenantId(PackageId packageId, TenantId tenantId);
}
