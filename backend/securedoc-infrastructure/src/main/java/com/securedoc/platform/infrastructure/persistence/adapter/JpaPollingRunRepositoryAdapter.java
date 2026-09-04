package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.port.out.PollingRunRepository;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.integration.*;
import com.securedoc.platform.infrastructure.persistence.entity.PollingRunEntity;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataPollingRunRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class JpaPollingRunRepositoryAdapter implements PollingRunRepository {
    private final SpringDataPollingRunRepository repository;
    public JpaPollingRunRepositoryAdapter(SpringDataPollingRunRepository repository){this.repository=repository;}

    @Override
    @Transactional
    public Optional<PollingRun> tryStart(TenantId tenantId, IntegrationConnectionId connectionId,
                                         PollingRunTrigger trigger, Instant now, Instant staleBefore) {
        String lockKey = connectionId.toString();
        repository.releaseStale(lockKey, staleBefore, now);
        PollingRun run = PollingRun.start(tenantId, connectionId, trigger, now);
        int inserted = repository.tryInsert(run.id(), tenantId.value(), connectionId.value(), trigger.name(), lockKey, now);
        return inserted == 1 ? Optional.of(run) : Optional.empty();
    }

    @Override public void save(PollingRun run){ repository.save(toEntity(run)); }
    @Override public Optional<PollingRun> findLatest(IntegrationConnectionId id){return repository.findFirstByIntegrationConnectionIdOrderByStartedAtDesc(id.value()).map(this::toDomain);}
    @Override public List<PollingRun> findRecent(TenantId tenantId, IntegrationConnectionId id, int limit){
        return repository.findTop50ByTenantIdAndIntegrationConnectionIdOrderByStartedAtDesc(tenantId.value(), id.value())
                .stream().limit(Math.max(1, Math.min(limit, 50))).map(this::toDomain).toList();
    }

    private PollingRunEntity toEntity(PollingRun r){
        PollingRunEntity e=new PollingRunEntity(); e.setId(r.id()); e.setTenantId(r.tenantId().value());
        e.setIntegrationConnectionId(r.integrationConnectionId().value()); e.setTriggerType(r.trigger().name()); e.setStatus(r.status().name());
        e.setActiveLockKey(r.activeLockKey()); e.setStartedAt(r.startedAt()); e.setCompletedAt(r.completedAt());
        e.setPackagesDiscovered(r.packagesDiscovered()); e.setDocumentsExpected(r.documentsExpected()); e.setDocumentsRetrieved(r.documentsRetrieved());
        e.setPackagesAcknowledged(r.packagesAcknowledged()); e.setFailureCategory(r.failureCategory()); e.setFailureDetail(r.failureDetail()); return e;
    }
    private PollingRun toDomain(PollingRunEntity e){
        return new PollingRun(e.getId(), TenantId.of(e.getTenantId()), IntegrationConnectionId.of(e.getIntegrationConnectionId()),
                PollingRunTrigger.valueOf(e.getTriggerType()), PollingRunStatus.valueOf(e.getStatus()), e.getActiveLockKey(),
                e.getStartedAt(), e.getCompletedAt(), e.getPackagesDiscovered(), e.getDocumentsExpected(), e.getDocumentsRetrieved(),
                e.getPackagesAcknowledged(), e.getFailureCategory(), e.getFailureDetail());
    }
}
