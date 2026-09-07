package com.securedoc.platform.application.support.service;

import com.securedoc.platform.application.port.out.ProcessingIncidentRepository;
import com.securedoc.platform.application.port.out.SupportIncidentSearchRepository;
import com.securedoc.platform.application.support.model.SupportIncidentDetail;
import com.securedoc.platform.application.support.model.SupportIncidentSearchCriteria;
import com.securedoc.platform.application.support.model.SupportIncidentSearchPage;
import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.processing.ProcessingIncident;
import java.util.UUID;

/**
 * Read-only support triage service. Durable incident state remains in processing_incidents/events.
 */
public class SupportIncidentQueryService {
    private final SupportIncidentSearchRepository searchRepository;
    private final ProcessingIncidentRepository incidentRepository;

    public SupportIncidentQueryService(SupportIncidentSearchRepository searchRepository,
                                       ProcessingIncidentRepository incidentRepository) {
        this.searchRepository = searchRepository;
        this.incidentRepository = incidentRepository;
    }

    public SupportIncidentSearchPage search(SupportIncidentSearchCriteria criteria) {
        return searchRepository.search(criteria);
    }

    public SupportIncidentDetail detail(UUID incidentId, TenantId tenantId) {
        var summary = searchRepository.findByIncidentId(incidentId, tenantId)
                .orElseThrow(() -> new NotFoundException("Support incident", incidentId.toString()));
        ProcessingIncident incident = incidentRepository.findByIdAndTenantId(incidentId, tenantId)
                .orElseThrow(() -> new NotFoundException("Support incident", incidentId.toString()));
        var events = incidentRepository.findEvents(incidentId, tenantId, 100).stream()
                .map(event -> new SupportIncidentDetail.Event(
                        event.eventType().name(),
                        event.attemptNumber(),
                        event.scope(),
                        event.reasonCode(),
                        event.retryable(),
                        event.correlationId().value(),
                        event.safeMetadata(),
                        event.occurredAt()))
                .toList();

        return new SupportIncidentDetail(
                incident.id(),
                incident.tenantId().value(),
                incident.packageId().value(),
                incident.documentId() == null ? null : incident.documentId().value(),
                summary.packageStatus(),
                summary.primaryIdentifierLabel(),
                summary.primaryIdentifierValue(),
                incident.status().name(),
                incident.businessOutcome(),
                businessImpact(incident, summary.packageStatus()),
                incident.businessContinuityUsed(),
                incident.stage().name(),
                incident.component(),
                incident.failureCategory(),
                incident.reasonCode(),
                incident.retryable(),
                incident.attempts(),
                incident.correlationId().value(),
                incident.safeSummary(),
                incident.processingFingerprint(),
                incident.safeMetadata(),
                incident.firstObservedAt(),
                incident.lastObservedAt(),
                incident.resolvedAt(),
                events);
    }

    static String businessImpact(ProcessingIncident incident) {
        return businessImpact(incident, null);
    }

    static String businessImpact(ProcessingIncident incident, String packageStatus) {
        if ("COMPLETED".equalsIgnoreCase(incident.businessOutcome())
                || "COMPLETED".equalsIgnoreCase(packageStatus)) {
            return "COMPLETED";
        }
        if (incident.businessContinuityUsed() || "BUSINESS_CONTINUED".equals(incident.status().name())) return "CONTINUED";
        return "BLOCKED";
    }
}
