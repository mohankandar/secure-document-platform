package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.business.BusinessIdentifierDefinition;
import com.securedoc.platform.domain.identity.TenantId;
import java.util.List;

public interface BusinessIdentifierDefinitionRepository {
    List<BusinessIdentifierDefinition> findActiveByTenantId(TenantId tenantId);
    List<BusinessIdentifierDefinition> findAllByTenantId(TenantId tenantId);
    void save(BusinessIdentifierDefinition definition);
    void saveAll(List<BusinessIdentifierDefinition> definitions);
}
