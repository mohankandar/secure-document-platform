package com.securedoc.platform.application.support.service;

import com.securedoc.platform.application.port.out.ProcessingIncidentRepository;
import com.securedoc.platform.application.port.out.SupportIncidentSearchRepository;
import com.securedoc.platform.application.support.model.SupportIncidentSearchCriteria;
import com.securedoc.platform.application.support.model.SupportIncidentSearchPage;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.processing.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SupportIncidentQueryServiceTest {
    private final TenantId tenant = TenantId.of(UUID.randomUUID());

    @Test
    void searchDelegatesTenantScopedCriteria() {
        var expected = new SupportIncidentSearchPage(List.of(), 0, 0, 25);
        SupportIncidentSearchRepository search = new SupportIncidentSearchRepository() {
            @Override public SupportIncidentSearchPage search(SupportIncidentSearchCriteria criteria) {
                assertEquals(tenant, criteria.tenantId());
                return expected;
            }
            @Override public Optional<com.securedoc.platform.application.support.model.SupportIncidentSummary> findByIncidentId(UUID incidentId, TenantId tenantId) {
                return Optional.empty();
            }
        };
        var service = new SupportIncidentQueryService(search, ProcessingIncidentRepository.none());
        var actual = service.search(new SupportIncidentSearchCriteria(
                tenant, "123", null, null, null, null, null, null, null, null,
                null, null, null, null, 0, 25));
        assertSame(expected, actual);
    }


    @Test
    void detailUsesNotFoundExceptionForMissingIncident() {
        SupportIncidentSearchRepository search = new SupportIncidentSearchRepository() {
            @Override public SupportIncidentSearchPage search(SupportIncidentSearchCriteria criteria) {
                return new SupportIncidentSearchPage(List.of(), 0, 0, 25);
            }
            @Override public Optional<com.securedoc.platform.application.support.model.SupportIncidentSummary> findByIncidentId(UUID incidentId, TenantId tenantId) {
                return Optional.empty();
            }
        };
        var service = new SupportIncidentQueryService(search, ProcessingIncidentRepository.none());

        assertThrows(com.securedoc.platform.application.exception.NotFoundException.class,
                () -> service.detail(UUID.randomUUID(), tenant));
    }

    @Test
    void businessImpactDistinguishesCompletedContinuedAndBlocked() {
        assertEquals("COMPLETED", SupportIncidentQueryService.businessImpact(incident(true, "COMPLETED", ProcessingIncidentStatus.BUSINESS_CONTINUED)));
        // Automatic recovery: technical incident is RESOLVED and the package ultimately completed.
        assertEquals("COMPLETED", SupportIncidentQueryService.businessImpact(incident(false, null, ProcessingIncidentStatus.RESOLVED), "COMPLETED"));
        // A technical RESOLVED flag by itself is not proof that the business transaction completed.
        assertEquals("BLOCKED", SupportIncidentQueryService.businessImpact(incident(false, null, ProcessingIncidentStatus.RESOLVED), "FAILED"));
        assertEquals("CONTINUED", SupportIncidentQueryService.businessImpact(incident(true, "IN_PROGRESS", ProcessingIncidentStatus.BUSINESS_CONTINUED)));
        assertEquals("BLOCKED", SupportIncidentQueryService.businessImpact(incident(false, null, ProcessingIncidentStatus.OPEN)));
    }

    private ProcessingIncident incident(boolean continuity, String outcome, ProcessingIncidentStatus status) {
        return new ProcessingIncident(
                UUID.randomUUID(), tenant, PackageId.generate(), DocumentId.generate(),
                ProcessingStage.AI_ANALYSIS, "AI", "DEPENDENCY_RESPONSE_ERROR", "AI_INVALID_RESPONSE",
                false, 2, CorrelationId.generate(), status, continuity, outcome,
                "Document processing failed", Map.of("aiModel", "qwen3.5:4b"), Map.of(),
                Instant.now(), Instant.now(), null);
    }
}
