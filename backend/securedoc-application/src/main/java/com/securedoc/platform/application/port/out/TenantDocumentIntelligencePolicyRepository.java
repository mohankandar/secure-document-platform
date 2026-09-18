package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.intelligence.TenantDocumentIntelligencePolicy;

import java.util.Optional;

public interface TenantDocumentIntelligencePolicyRepository {
    Optional<TenantDocumentIntelligencePolicy> findByTenantId(TenantId tenantId);
    void save(TenantDocumentIntelligencePolicy policy);
}
