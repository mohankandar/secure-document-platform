package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.tenant.Tenant;

import java.util.List;
import java.util.Optional;

/**
 * Outbound port for querying tenant information.
 */
public interface TenantRepository {

    Optional<Tenant> findById(TenantId tenantId);

    Optional<Tenant> findBySlug(String slug);

    List<Tenant> findAll();
}
