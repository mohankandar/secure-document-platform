package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.port.out.ProcessingIncidentRepository;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.processing.*;
import com.securedoc.platform.infrastructure.persistence.entity.ProcessingIncidentEntity;
import com.securedoc.platform.infrastructure.persistence.entity.ProcessingIncidentEventEntity;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataProcessingIncidentEventRepository;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataProcessingIncidentRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class JpaProcessingIncidentRepositoryAdapter implements ProcessingIncidentRepository {
    private final SpringDataProcessingIncidentRepository incidentRepository;
    private final SpringDataProcessingIncidentEventRepository eventRepository;

    public JpaProcessingIncidentRepositoryAdapter(SpringDataProcessingIncidentRepository incidentRepository,
                                                  SpringDataProcessingIncidentEventRepository eventRepository) {
        this.incidentRepository = incidentRepository;
        this.eventRepository = eventRepository;
    }

    @Override public void save(ProcessingIncident incident) { incidentRepository.save(toEntity(incident)); }

    @Override
    public Optional<ProcessingIncident> findByIdAndTenantId(UUID incidentId, TenantId tenantId) {
        return incidentRepository.findByIdAndTenantId(incidentId, tenantId.value()).map(this::toDomain);
    }

    @Override
    public Optional<ProcessingIncident> findLatestByPackageIdAndTenantId(PackageId packageId, TenantId tenantId) {
        return incidentRepository.findFirstByTenantIdAndPackageIdOrderByLastObservedAtDesc(tenantId.value(), packageId.value())
                .map(this::toDomain);
    }

    @Override
    public List<ProcessingIncident> findRecentByPackageIdAndTenantId(PackageId packageId, TenantId tenantId, int limit) {
        return incidentRepository.findTop50ByTenantIdAndPackageIdOrderByLastObservedAtDesc(tenantId.value(), packageId.value())
                .stream().limit(Math.max(1, Math.min(limit, 50))).map(this::toDomain).toList();
    }

    @Override
    public List<ProcessingIncident> findRecentByTenantId(TenantId tenantId, int limit) {
        return incidentRepository.findTop100ByTenantIdOrderByLastObservedAtDesc(tenantId.value())
                .stream().limit(Math.max(1, Math.min(limit, 100))).map(this::toDomain).toList();
    }

    @Override public void saveEvent(ProcessingIncidentEvent event) { eventRepository.save(toEntity(event)); }

    @Override
    public List<ProcessingIncidentEvent> findEvents(UUID incidentId, TenantId tenantId, int limit) {
        return eventRepository.findTop100ByTenantIdAndIncidentIdOrderByOccurredAtAsc(tenantId.value(), incidentId)
                .stream().limit(Math.max(1, Math.min(limit, 100))).map(this::toDomain).toList();
    }

    private ProcessingIncidentEntity toEntity(ProcessingIncident i) {
        ProcessingIncidentEntity e = new ProcessingIncidentEntity();
        e.setId(i.id()); e.setTenantId(i.tenantId().value()); e.setPackageId(i.packageId().value());
        e.setDocumentId(i.documentId() == null ? null : i.documentId().value()); e.setStage(i.stage().name());
        e.setComponent(i.component()); e.setFailureCategory(i.failureCategory()); e.setReasonCode(i.reasonCode());
        e.setRetryable(i.retryable()); e.setAttemptCount(i.attempts()); e.setCorrelationId(i.correlationId().value());
        e.setStatus(i.status().name()); e.setBusinessContinuityUsed(i.businessContinuityUsed());
        e.setBusinessOutcome(i.businessOutcome()); e.setSafeSummary(i.safeSummary());
        e.setProcessingFingerprint(i.processingFingerprint()); e.setSafeMetadata(i.safeMetadata());
        e.setFirstObservedAt(i.firstObservedAt()); e.setLastObservedAt(i.lastObservedAt()); e.setResolvedAt(i.resolvedAt());
        return e;
    }

    private ProcessingIncident toDomain(ProcessingIncidentEntity e) {
        return new ProcessingIncident(e.getId(), TenantId.of(e.getTenantId()), PackageId.of(e.getPackageId()),
                e.getDocumentId() == null ? null : DocumentId.of(e.getDocumentId()), ProcessingStage.valueOf(e.getStage()),
                e.getComponent(), e.getFailureCategory(), e.getReasonCode(), e.isRetryable(), e.getAttemptCount(),
                CorrelationId.of(e.getCorrelationId()), ProcessingIncidentStatus.valueOf(e.getStatus()),
                e.isBusinessContinuityUsed(), e.getBusinessOutcome(), e.getSafeSummary(),
                map(e.getProcessingFingerprint()), map(e.getSafeMetadata()), e.getFirstObservedAt(), e.getLastObservedAt(), e.getResolvedAt());
    }

    private ProcessingIncidentEventEntity toEntity(ProcessingIncidentEvent i) {
        ProcessingIncidentEventEntity e = new ProcessingIncidentEventEntity();
        e.setId(i.id()); e.setIncidentId(i.incidentId()); e.setTenantId(i.tenantId().value());
        e.setEventType(i.eventType().name()); e.setAttemptNumber(i.attemptNumber()); e.setScope(i.scope());
        e.setReasonCode(i.reasonCode()); e.setRetryable(i.retryable()); e.setCorrelationId(i.correlationId().value());
        e.setSafeMetadata(i.safeMetadata()); e.setOccurredAt(i.occurredAt()); return e;
    }

    private ProcessingIncidentEvent toDomain(ProcessingIncidentEventEntity e) {
        return new ProcessingIncidentEvent(e.getId(), e.getIncidentId(), TenantId.of(e.getTenantId()),
                ProcessingIncidentEventType.valueOf(e.getEventType()), e.getAttemptNumber(), e.getScope(), e.getReasonCode(),
                e.getRetryable(), CorrelationId.of(e.getCorrelationId()), map(e.getSafeMetadata()), e.getOccurredAt());
    }

    private Map<String,String> map(Map<String,String> value) { return value == null ? Map.of() : value; }
}
