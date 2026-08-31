package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.lifecycle.TenantLifecyclePolicy;

import java.util.Optional;

public interface TenantLifecyclePolicyRepository {
    Optional<TenantLifecyclePolicy> findByTenantId(TenantId tenantId);
    void save(TenantLifecyclePolicy policy);
}
