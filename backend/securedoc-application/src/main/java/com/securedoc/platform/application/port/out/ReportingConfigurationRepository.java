package com.securedoc.platform.application.port.out;

import com.securedoc.platform.application.business.admin.ReportingConfiguration;
import com.securedoc.platform.domain.identity.TenantId;

public interface ReportingConfigurationRepository {
    ReportingConfiguration findByTenantId(TenantId tenantId);
    void save(TenantId tenantId, ReportingConfiguration configuration);
}
