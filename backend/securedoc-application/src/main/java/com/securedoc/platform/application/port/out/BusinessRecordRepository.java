package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.business.BusinessRecord;
import com.securedoc.platform.domain.identity.BusinessIdentifierDefinitionId;
import com.securedoc.platform.domain.identity.BusinessRecordId;
import com.securedoc.platform.domain.identity.TenantId;
import java.util.Optional;

public interface BusinessRecordRepository {
    void save(BusinessRecord record);
    Optional<BusinessRecord> findByIdAndTenantId(BusinessRecordId id, TenantId tenantId);
    Optional<BusinessRecord> findByPrimaryIdentifier(TenantId tenantId, BusinessIdentifierDefinitionId definitionId, String normalizedValue);
}
