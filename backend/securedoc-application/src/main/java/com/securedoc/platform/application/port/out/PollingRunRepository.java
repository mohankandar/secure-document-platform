package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.integration.PollingRun;
import com.securedoc.platform.domain.integration.PollingRunTrigger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PollingRunRepository {
    Optional<PollingRun> tryStart(TenantId tenantId, IntegrationConnectionId connectionId,
                                  PollingRunTrigger trigger, Instant now, Instant staleBefore);
    void save(PollingRun run);
    Optional<PollingRun> findLatest(IntegrationConnectionId connectionId);
    List<PollingRun> findRecent(TenantId tenantId, IntegrationConnectionId connectionId, int limit);
}
