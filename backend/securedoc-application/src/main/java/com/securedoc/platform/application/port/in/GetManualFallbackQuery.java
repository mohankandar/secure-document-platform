package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.manual.result.ManualFallbackDetail;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

public interface GetManualFallbackQuery {
    ManualFallbackDetail execute(PackageId packageId, TenantId tenantId);
}
