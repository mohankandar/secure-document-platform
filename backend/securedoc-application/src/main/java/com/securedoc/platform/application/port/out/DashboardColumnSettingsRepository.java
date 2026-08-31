package com.securedoc.platform.application.port.out;

import com.securedoc.platform.application.operations.model.DashboardColumnSetting;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.List;

public interface DashboardColumnSettingsRepository {
    List<DashboardColumnSetting> findByTenantId(TenantId tenantId);
    void replaceForTenant(TenantId tenantId, List<DashboardColumnSetting> settings);
}
