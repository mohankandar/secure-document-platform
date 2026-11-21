package com.securedoc.platform.application.port.in;

import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

/**
 * Task 0028: rebuilds a case run's master export once from the stored redacted copies, before a later retry. The same
 * case run and the same idempotency identities, so the destination still recognises a repeat. Sends nothing.
 */
public interface RebuildPackageExportUseCase {

    /**
     * @return true when the stored submission was rebuilt; false when there was nothing to rebuild (no submission yet,
     *         or it was already delivered)
     */
    boolean rebuild(TenantId tenantId, PackageId packageId, CorrelationId correlationId);
}
