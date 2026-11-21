package com.securedoc.platform.application.routing.service;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.*;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.artifact.*;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.*;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.integration.*;
import com.securedoc.platform.domain.routing.*;
import com.securedoc.platform.domain.vo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 0023 (C4, #181, #189, #190, #192): routing failures and automatic-send refusals are recorded with a code,
 * audited, and end up in the attention list (RETRY_PENDING / ATTENTION_REQUIRED delivery rows). No retries.
 */
class DerivedRoutingFailureRecordingTest {
    private final TenantId tenant = TenantId.generate();
    private DocumentPackage pkg;
    private Document source;
    private IntegrationConnection destination;
    private Artifact derivedArtifact;
    private RoutingRule rule;
    private DerivedDeliveryRepository deliveries;
    private IntegrationConnectionRepository connections;
    private ArtifactRepository artifacts;
    private StoragePort storage;
    private DerivedDestinationDeliveryPort port;
    private PackageRepository packages;
    private AuditService audit;
    private DerivedDeliveryService deliveryService;

    @BeforeEach
    void setUp() {
        pkg = DocumentPackage.createIntake(tenant, PackageType.API_SUBMISSION, null, null, null,
                ActorId.generate(), PackageMetadata.empty(), SensitivityLevel.STANDARD);
        source = Document.create(tenant, pkg.id(), FileName.of("MARKER-source-name.pdf"), MimeType.pdf(), 10,
                Checksum.sha256("a".repeat(64)), DocumentType.PDF, SensitivityLevel.STANDARD);
        pkg.addDocument(source);
        destination = IntegrationConnection.create(tenant, "Archive", null,
                IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY, IntegrationDirection.OUTBOUND,
                IntegrationProviderType.HTTP_API, IntegrationAuthenticationType.NONE, null,
                "https://example.test/derived", null, null, 3, 0, 24, 10, 10_000_000L, 100,
                IntegrationAcknowledgementMode.DESTINATION_RECEIPT, true, Instant.now());
        derivedArtifact = Artifact.create(tenant, source.id(), ArtifactType.DERIVED_REDACTED, MimeType.pdf(), 10,
                Checksum.sha256("b".repeat(64)));
        derivedArtifact.markAvailable(ArtifactStorageReference.local("derived/test.pdf"));
        rule = mock(RoutingRule.class);
        when(rule.id()).thenReturn(UUID.randomUUID());
        when(rule.destinationConnectionId()).thenReturn(destination.id());
        when(rule.destinationLabel()).thenReturn("Archive");

        deliveries = mock(DerivedDeliveryRepository.class);
        connections = mock(IntegrationConnectionRepository.class);
        artifacts = mock(ArtifactRepository.class);
        storage = mock(StoragePort.class);
        port = mock(DerivedDestinationDeliveryPort.class);
        packages = mock(PackageRepository.class);
        audit = mock(AuditService.class);
        when(packages.findByIdAndTenantId(pkg.id(), tenant)).thenReturn(Optional.of(pkg));
        when(connections.findByTenantIdAndId(tenant, destination.id())).thenReturn(Optional.of(destination));
        when(artifacts.findByIdAndTenantId(derivedArtifact.id(), tenant)).thenReturn(Optional.of(derivedArtifact));
        deliveryService = new DerivedDeliveryService(deliveries, connections, artifacts, storage, port, audit, packages);
    }

    private DerivedDelivery pendingDelivery() {
        DerivedDelivery d = DerivedDelivery.create(tenant, pkg.id(), source.id(), 1, rule,
                destination.configurationVersion(), List.of(1), derivedArtifact.id(), "part.pdf", Instant.now());
        when(deliveries.findByTenantIdAndId(tenant, d.id())).thenReturn(Optional.of(d));
        return d;
    }

    private String refusalAuditDetail(DerivedDelivery d) {
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit).recordFailure(eq(tenant), any(), eq(AuditAction.DERIVED_DOCUMENT_DELIVERY_FAILED),
                eq("DerivedDelivery"), eq(d.id().toString()), any(CorrelationId.class), detail.capture());
        return detail.getValue();
    }

    @Test
    void autoSendToADeletedDestinationIsRecordedWithACodeInsteadOfStayingPending() {
        DerivedDelivery d = pendingDelivery();
        when(connections.findByTenantIdAndId(tenant, destination.id())).thenReturn(Optional.empty());

        assertThrows(ValidationException.class, () -> deliveryService.autoSend(tenant, d.id(), CorrelationId.generate()));

        assertEquals(DerivedDeliveryStatus.ATTENTION_REQUIRED, d.status());
        assertEquals(DerivedDeliveryService.DESTINATION_NOT_FOUND, d.lastFailureCategory());
        verify(deliveries).save(d);
        assertEquals("Derived delivery refused before sending: code=LV-DLV-006, detail=DERIVED_DESTINATION_NOT_FOUND, retryable=false",
                refusalAuditDetail(d));
        verify(port, never()).deliver(any(), any());
    }

    @Test
    void autoSendWithAnUnavailableArtifactIsRecordedWithACode() {
        DerivedDelivery d = pendingDelivery();
        Artifact gone = Artifact.create(tenant, source.id(), ArtifactType.DERIVED_REDACTED, MimeType.pdf(), 10,
                Checksum.sha256("c".repeat(64)));
        when(artifacts.findByIdAndTenantId(derivedArtifact.id(), tenant)).thenReturn(Optional.of(gone));

        assertThrows(ValidationException.class, () -> deliveryService.autoSend(tenant, d.id(), CorrelationId.generate()));

        assertEquals(DerivedDeliveryStatus.ATTENTION_REQUIRED, d.status());
        assertEquals(DerivedDeliveryService.ARTIFACT_UNAVAILABLE, d.lastFailureCategory());
        assertTrue(refusalAuditDetail(d).contains("code=LV-DLV-011, detail=DERIVED_ARTIFACT_UNAVAILABLE"));
    }

    @Test
    void autoSendWhenTheSourceDocumentIsGoneIsRecordedWithACode() {
        DerivedDelivery d = pendingDelivery();
        when(packages.findByIdAndTenantId(pkg.id(), tenant)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> deliveryService.autoSend(tenant, d.id(), CorrelationId.generate()));

        assertEquals(DerivedDeliveryService.SOURCE_NOT_FOUND, d.lastFailureCategory());
        assertTrue(refusalAuditDetail(d).contains("code=LV-DLV-011, detail=DERIVED_SOURCE_NOT_FOUND"));
    }

    @Test
    void aDisabledDestinationIsNowAuditedToo() {
        DerivedDelivery d = pendingDelivery();
        IntegrationConnection disabled = mock(IntegrationConnection.class);
        when(disabled.purpose()).thenReturn(IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY);
        when(disabled.direction()).thenReturn(IntegrationDirection.OUTBOUND);
        when(disabled.enabled()).thenReturn(false);
        when(connections.findByTenantIdAndId(tenant, destination.id())).thenReturn(Optional.of(disabled));

        assertThrows(ValidationException.class, () -> deliveryService.autoSend(tenant, d.id(), CorrelationId.generate()));

        assertEquals(DerivedDeliveryService.DESTINATION_DISABLED, d.lastFailureCategory());
        assertTrue(refusalAuditDetail(d).contains("code=LV-DLV-006, detail=DERIVED_DESTINATION_DISABLED"));
    }

    private DerivedRoutingService routing(DocumentRepository documents, RoutingRuleRepository rules,
                                          RoutingMarkerDetectionPort detector, DerivedPdfBuilderPort builder) {
        return new DerivedRoutingService(rules, deliveries, connections, documents, artifacts, storage, detector,
                builder, deliveryService, audit);
    }

    private List<DerivedDelivery> savedAttention() {
        ArgumentCaptor<DerivedDelivery> saved = ArgumentCaptor.forClass(DerivedDelivery.class);
        verify(deliveries, atLeastOnce()).save(saved.capture());
        return saved.getAllValues().stream().filter(d -> d.status() == DerivedDeliveryStatus.ATTENTION_REQUIRED).toList();
    }

    private String attentionAuditDetail() {
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit, atLeastOnce()).recordFailure(eq(tenant), any(), eq(AuditAction.ROUTING_ATTENTION_REQUIRED),
                anyString(), anyString(), any(CorrelationId.class), detail.capture());
        return String.join("\n", detail.getAllValues());
    }

    @Test
    void aDocumentWithNoRedactedMasterBecomesAnAttentionItemInsteadOfASilentReturn() {
        DocumentRepository documents = mock(DocumentRepository.class);
        RoutingRuleRepository rules = mock(RoutingRuleRepository.class);
        when(rules.findEnabledByTenantId(tenant)).thenReturn(List.of(rule));
        when(documents.findByPackageIdAndTenantId(pkg.id(), tenant)).thenReturn(List.of(source));
        when(artifacts.findByDocumentIdAndTenantId(source.id(), tenant)).thenReturn(List.of());

        routing(documents, rules, mock(RoutingMarkerDetectionPort.class), mock(DerivedPdfBuilderPort.class))
                .processPackage(pkg.id(), tenant, 1, CorrelationId.generate());

        List<DerivedDelivery> attention = savedAttention();
        assertEquals(1, attention.size());
        assertEquals(DerivedRoutingService.MASTER_UNAVAILABLE, attention.get(0).lastFailureCategory());
        assertEquals(source.id(), attention.get(0).sourceDocumentId());
        assertTrue(attentionAuditDetail().contains("code=LV-DLV-011, detail=ROUTING_MASTER_UNAVAILABLE"));
    }

    @Test
    void anUnexpectedFailureForOneDocumentIsRecordedAndTheOtherDocumentsAreStillRouted() {
        Document second = Document.create(tenant, pkg.id(), FileName.of("second.pdf"), MimeType.pdf(), 10,
                Checksum.sha256("d".repeat(64)), DocumentType.PDF, SensitivityLevel.STANDARD);
        DocumentRepository documents = mock(DocumentRepository.class);
        RoutingRuleRepository rules = mock(RoutingRuleRepository.class);
        when(rules.findEnabledByTenantId(tenant)).thenReturn(List.of(rule));
        when(documents.findByPackageIdAndTenantId(pkg.id(), tenant)).thenReturn(List.of(source, second));
        when(artifacts.findByDocumentIdAndTenantId(source.id(), tenant)).thenThrow(new IllegalStateException("db"));
        when(artifacts.findByDocumentIdAndTenantId(second.id(), tenant)).thenReturn(List.of());

        routing(documents, rules, mock(RoutingMarkerDetectionPort.class), mock(DerivedPdfBuilderPort.class))
                .processPackage(pkg.id(), tenant, 1, CorrelationId.generate());

        List<DerivedDelivery> attention = savedAttention();
        assertEquals(2, attention.size());
        assertEquals(DerivedRoutingService.DOCUMENT_ROUTING_FAILED, attention.get(0).lastFailureCategory());
        assertEquals(source.id(), attention.get(0).sourceDocumentId());
        assertEquals(DerivedRoutingService.MASTER_UNAVAILABLE, attention.get(1).lastFailureCategory());
        assertFalse(attentionAuditDetail().contains("db"));
    }

    @Test
    void aBuildFailureIsAnAttentionItemWithItsCode() throws Exception {
        Artifact master = Artifact.create(tenant, source.id(), ArtifactType.REDACTED, MimeType.pdf(), 10,
                Checksum.sha256("e".repeat(64)));
        master.markAvailable(ArtifactStorageReference.local("redacted/master.pdf"));
        DocumentRepository documents = mock(DocumentRepository.class);
        RoutingRuleRepository rules = mock(RoutingRuleRepository.class);
        RoutingMarkerDetectionPort detector = mock(RoutingMarkerDetectionPort.class);
        DerivedPdfBuilderPort builder = mock(DerivedPdfBuilderPort.class);
        when(rules.findEnabledByTenantId(tenant)).thenReturn(List.of(rule));
        when(documents.findByPackageIdAndTenantId(pkg.id(), tenant)).thenReturn(List.of(source));
        when(artifacts.findByDocumentIdAndTenantId(source.id(), tenant)).thenReturn(List.of(master));
        when(storage.retrieve(any())).thenAnswer(call -> new ByteArrayInputStream("PDF".getBytes()));
        RoutingMarkerDetectionResult result = new RoutingMarkerDetectionResult(Map.of(rule.id(), List.of(1)), Set.of());
        when(detector.detect(any(), any())).thenReturn(result);
        when(builder.build(any(), any())).thenThrow(new IllegalStateException("pdf"));

        routing(documents, rules, detector, builder).processPackage(pkg.id(), tenant, 1, CorrelationId.generate());

        List<DerivedDelivery> attention = savedAttention();
        assertEquals(1, attention.size());
        assertEquals(DerivedRoutingService.BUILD_FAILED, attention.get(0).lastFailureCategory());
        assertEquals(List.of(1), attention.get(0).pageNumbers());
    }

    @Test
    void withExportJobsOnASplitDocumentIsQueuedAsItsOwnJobInsteadOfSentAtOnce() throws Exception {
        Artifact master = Artifact.create(tenant, source.id(), ArtifactType.REDACTED, MimeType.pdf(), 10,
                Checksum.sha256("e".repeat(64)));
        master.markAvailable(ArtifactStorageReference.local("redacted/master.pdf"));
        DocumentRepository documents = mock(DocumentRepository.class);
        RoutingRuleRepository rules = mock(RoutingRuleRepository.class);
        RoutingMarkerDetectionPort detector = mock(RoutingMarkerDetectionPort.class);
        DerivedPdfBuilderPort builder = mock(DerivedPdfBuilderPort.class);
        when(rules.findEnabledByTenantId(tenant)).thenReturn(List.of(rule));
        when(documents.findByPackageIdAndTenantId(pkg.id(), tenant)).thenReturn(List.of(source));
        when(artifacts.findByDocumentIdAndTenantId(source.id(), tenant)).thenReturn(List.of(master));
        when(storage.retrieve(any())).thenAnswer(call -> new ByteArrayInputStream("PDF".getBytes()));
        when(storage.storeForProcessingCycle(any(), any(), anyInt(), anyString(), any(), anyLong(), anyString()))
                .thenReturn(ArtifactStorageReference.local("derived/split.pdf"));
        RoutingMarkerDetectionResult detected = new RoutingMarkerDetectionResult(Map.of(rule.id(), List.of(1)), Set.of());
        when(detector.detect(any(), any())).thenReturn(detected);
        when(builder.build(any(), any())).thenAnswer(call -> new ReplayableContent() {
            @Override public java.io.InputStream openStream() { return new ByteArrayInputStream("SPLIT".getBytes()); }
            @Override public long contentLength() { return 5; }
            @Override public void close() { }
        });
        var scheduler = mock(com.securedoc.platform.application.pipeline.SplitExportScheduler.class);

        new DerivedRoutingService(rules, deliveries, connections, documents, artifacts, storage, detector, builder,
                deliveryService, audit, scheduler).processPackage(pkg.id(), tenant, 1, CorrelationId.generate());

        // Task 0027: one SPLIT_EXPORT job for the split document; nothing is sent from here.
        verify(scheduler).queueSplitExport(eq(tenant), eq(pkg.id()), eq(source.id()), any(UUID.class),
                eq(destination.id()), eq(1), any());
        verify(port, never()).deliver(any(), any());
    }

    @Test
    void aRoutingFailureAfterTheMasterExportIsAuditedOnTheCaseAndListedPerDocument() {
        DocumentRepository documents = mock(DocumentRepository.class);
        when(documents.findByPackageIdAndTenantId(pkg.id(), tenant)).thenReturn(List.of(source));

        routing(documents, mock(RoutingRuleRepository.class), mock(RoutingMarkerDetectionPort.class),
                mock(DerivedPdfBuilderPort.class))
                .recordRoutingFailure(pkg.id(), tenant, 2, "DERIVED_ROUTING_POST_EXPORT_FAILED", CorrelationId.generate());

        verify(audit).recordFailure(eq(tenant), any(), eq(AuditAction.ROUTING_ATTENTION_REQUIRED),
                eq("DocumentPackage"), eq(pkg.id().toString()), any(CorrelationId.class),
                eq("Derived routing failed after master export: code=LV-DLV-013, detail=DERIVED_ROUTING_POST_EXPORT_FAILED, cycle=2"));
        List<DerivedDelivery> attention = savedAttention();
        assertEquals(1, attention.size());
        assertEquals("DERIVED_ROUTING_POST_EXPORT_FAILED", attention.get(0).lastFailureCategory());
        assertEquals(2, attention.get(0).exportCycle());
    }
}
