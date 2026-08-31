package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Optional;

public interface TenantRedactionPolicyRepository {
    Optional<TenantRedactionPolicy> findByTenantId(TenantId tenantId);
    void save(TenantRedactionPolicy policy);
}
