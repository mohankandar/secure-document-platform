package com.securedoc.platform.application.port.in;

import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

/** Creates/delivers derived redacted routing outputs without changing master export success. */
public interface ProcessDerivedRoutingUseCase {
    void processPackage(PackageId packageId, TenantId tenantId, int exportCycle, CorrelationId correlationId);
}
