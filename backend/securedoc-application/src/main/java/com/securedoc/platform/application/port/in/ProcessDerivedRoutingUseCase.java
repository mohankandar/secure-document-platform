package com.securedoc.platform.application.port.in;

import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

/** Creates/delivers derived redacted routing outputs without changing master export success. */
public interface ProcessDerivedRoutingUseCase {
    void processPackage(PackageId packageId, TenantId tenantId, int exportCycle, CorrelationId correlationId);

    /**
     * Task 0023 (C4, #181): records a routing failure that escaped {@link #processPackage} after the master export:
     * an audit row with the code and an attention item per document. Never retries. The default does nothing
     * (for implementations without derived routing).
     */
    default void recordRoutingFailure(PackageId packageId, TenantId tenantId, int exportCycle, String code,
                                      CorrelationId correlationId) {
    }
}
