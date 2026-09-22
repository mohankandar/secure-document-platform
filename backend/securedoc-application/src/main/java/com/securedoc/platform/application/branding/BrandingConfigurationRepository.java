package com.securedoc.platform.application.branding;

import com.securedoc.platform.domain.identity.TenantId;

import java.util.List;
import java.util.Optional;

public interface BrandingConfigurationRepository {
    Optional<BrandingConfiguration> findDeployment();
    Optional<BrandingConfiguration> findTenant(TenantId tenantId);
    List<BrandingConfiguration> findTenantOverrides();
    BrandingConfiguration save(BrandingConfiguration configuration);
    void deleteTenant(TenantId tenantId);
}
