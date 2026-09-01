package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.technical.model.TechnicalExceptionDetail;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

public interface GetTechnicalExceptionQuery {
    TechnicalExceptionDetail execute(PackageId packageId, TenantId tenantId);
}
