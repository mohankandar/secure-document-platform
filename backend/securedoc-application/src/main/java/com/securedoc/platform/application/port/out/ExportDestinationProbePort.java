package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.TenantId;

/**
 * A destination's health check (task 0027; task 0028 runs it at once on a connection failure). The existing connection
 * test for the document store and the split destinations; for the local export folder (its derived id) a test file is written and
 * deleted.
 */
public interface ExportDestinationProbePort {

    /** Returns when the destination answered; throws when it did not. Sends no case and no document. */
    void probe(TenantId tenantId, IntegrationConnectionId connectionId);
}
