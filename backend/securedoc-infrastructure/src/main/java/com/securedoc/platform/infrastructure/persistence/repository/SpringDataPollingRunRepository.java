package com.securedoc.platform.infrastructure.persistence.repository;

import com.securedoc.platform.infrastructure.persistence.entity.PollingRunEntity;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataPollingRunRepository extends JpaRepository<PollingRunEntity, UUID> {
    Optional<PollingRunEntity> findFirstByIntegrationConnectionIdOrderByStartedAtDesc(UUID connectionId);
    List<PollingRunEntity> findTop50ByTenantIdAndIntegrationConnectionIdOrderByStartedAtDesc(UUID tenantId, UUID connectionId);

    @Modifying
    @Transactional
    @Query(value = """
            update polling_runs set status='FAILED', active_lock_key=null, completed_at=:now,
              failure_category='STALE_POLLING_RUN', failure_detail='Previous polling run exceeded its lease and was released'
            where active_lock_key=:lockKey and started_at < :staleBefore
            """, nativeQuery = true)
    int releaseStale(@Param("lockKey") String lockKey, @Param("staleBefore") Instant staleBefore, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query(value = """
            insert into polling_runs (id, tenant_id, integration_connection_id, trigger_type, status, active_lock_key,
              started_at, packages_discovered, documents_expected, documents_retrieved, packages_acknowledged)
            values (:id, :tenantId, :connectionId, :triggerType, 'RUNNING', :lockKey, :startedAt, 0, 0, 0, 0)
            on conflict (active_lock_key) do nothing
            """, nativeQuery = true)
    int tryInsert(@Param("id") UUID id, @Param("tenantId") UUID tenantId, @Param("connectionId") UUID connectionId,
                  @Param("triggerType") String triggerType, @Param("lockKey") String lockKey,
                  @Param("startedAt") Instant startedAt);
}
