package com.securedoc.platform.application.support.service;

import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.ProcessingIncidentRepository;
import com.securedoc.platform.application.port.out.SupportIncidentSearchRepository;
import com.securedoc.platform.application.support.model.DocumentPosition;
import com.securedoc.platform.application.support.model.SupportIncidentSummary;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.processing.ProcessingIncident;
import com.securedoc.platform.domain.processing.ProcessingIncidentStatus;
import com.securedoc.platform.domain.processing.ProcessingStage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/** Task 0038: Support's problem detail carries the document's position and page, from the case's documents. */
class SupportDocumentPositionTest {
    private final TenantId tenant = TenantId.generate();
    private final PackageId pkg = PackageId.generate();
    private final UUID incidentId = UUID.randomUUID();

    @Test
    void aProblemAboutOneDocumentSaysWhereItSitsAndACaseWideProblemDoesNot() {
        Document first = document(Instant.parse("2026-11-22T10:00:00Z"));
        Document second = document(Instant.parse("2026-11-22T10:00:05Z"));
        DocumentRepository documents = mock(DocumentRepository.class);
        when(documents.findByPackageIdAndTenantId(pkg, tenant)).thenReturn(List.of(second, first));

        assertEquals(new DocumentPosition(2, 2, 14),
                service(incident(second.id(), Map.of("page", "14")), documents).detail(incidentId, tenant)
                        .documentPosition());
        assertNull(service(incident(null, Map.of()), documents).detail(incidentId, tenant).documentPosition());
        assertNull(service(incident(second.id(), Map.of()), null).detail(incidentId, tenant).documentPosition(),
                "without the documents nothing is guessed");
    }

    private SupportIncidentQueryService service(ProcessingIncident incident, DocumentRepository documents) {
        SupportIncidentSearchRepository search = mock(SupportIncidentSearchRepository.class);
        when(search.findByIncidentId(incidentId, tenant)).thenReturn(Optional.of(mock(SupportIncidentSummary.class)));
        ProcessingIncidentRepository incidents = mock(ProcessingIncidentRepository.class);
        when(incidents.findByIdAndTenantId(incidentId, tenant)).thenReturn(Optional.of(incident));
        when(incidents.findEvents(any(), any(), anyInt())).thenReturn(List.of());
        return new SupportIncidentQueryService(search, incidents, null, documents);
    }

    private ProcessingIncident incident(DocumentId documentId, Map<String, String> metadata) {
        ProcessingIncident incident = mock(ProcessingIncident.class);
        when(incident.id()).thenReturn(incidentId);
        when(incident.tenantId()).thenReturn(tenant);
        when(incident.packageId()).thenReturn(pkg);
        when(incident.documentId()).thenReturn(documentId);
        when(incident.status()).thenReturn(ProcessingIncidentStatus.OPEN);
        when(incident.stage()).thenReturn(ProcessingStage.values()[0]);
        when(incident.correlationId()).thenReturn(CorrelationId.generate());
        when(incident.safeMetadata()).thenReturn(metadata);
        when(incident.reasonCode()).thenReturn("OCR_TIMEOUT");
        return incident;
    }

    private Document document(Instant uploadedAt) {
        Document d = mock(Document.class);
        DocumentId id = DocumentId.generate();
        when(d.id()).thenReturn(id);
        when(d.uploadedAt()).thenReturn(uploadedAt);
        return d;
    }
}
