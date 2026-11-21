package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Map;
import java.util.Optional;

/**
 * Task 0028: the retention period per export destination, set by the Business Admin. Hours only. The master
 * destination has no connection id; a split (routing) destination is its connection. No row means the default.
 */
public interface ExportRetentionRepository {

    Optional<Integer> masterHours(TenantId tenantId);

    Map<IntegrationConnectionId, Integer> splitHours(TenantId tenantId);

    /** Sets the master period; {@code hours} null removes it (back to the default). */
    void setMaster(TenantId tenantId, Integer hours, ActorId actorId);

    /** Sets a split destination's period; {@code hours} null removes it (back to the master's). */
    void setSplit(TenantId tenantId, IntegrationConnectionId destinationId, Integer hours, ActorId actorId);

    /** Nothing set anywhere: every copy follows today's rules. */
    static ExportRetentionRepository none() {
        return new ExportRetentionRepository() {
            @Override public Optional<Integer> masterHours(TenantId tenantId) { return Optional.empty(); }
            @Override public Map<IntegrationConnectionId, Integer> splitHours(TenantId tenantId) { return Map.of(); }
            @Override public void setMaster(TenantId tenantId, Integer hours, ActorId actorId) {
                throw new UnsupportedOperationException("No retention store");
            }
            @Override public void setSplit(TenantId tenantId, IntegrationConnectionId destinationId, Integer hours,
                                           ActorId actorId) {
                throw new UnsupportedOperationException("No retention store");
            }
        };
    }
}
