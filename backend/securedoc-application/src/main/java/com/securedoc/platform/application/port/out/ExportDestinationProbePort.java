package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.TenantId;

/** Checks that a paused export destination answers again, with the existing connection test (task 0027). */
public interface ExportDestinationProbePort {

    /** Returns when the destination answered; throws when it did not. Sends no case and no document. */
    void probe(TenantId tenantId, IntegrationConnectionId connectionId);
}
