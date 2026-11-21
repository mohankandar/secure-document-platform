package com.securedoc.platform.application.routing.service;

import com.securedoc.platform.application.port.out.*;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.artifact.*;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.*;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.integration.*;
import com.securedoc.platform.domain.routing.DerivedDelivery;
import com.securedoc.platform.domain.routing.RoutingRule;
import com.securedoc.platform.domain.vo.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Task 0021: every document cut from a source document carries that source document's level, plus the case level. */
class DerivedDeliverySensitivityTest {

    @Test
    void aSplitDocumentCarriesItsSourceDocumentLevelAndTheCaseLevel() {
        TenantId tenant = TenantId.generate();
        DocumentPackage pkg = DocumentPackage.createIntake(tenant, PackageType.API_SUBMISSION, null, null, null,
                ActorId.generate(), PackageMetadata.empty(), SensitivityLevel.CONFIDENTIAL);
        Document source = Document.create(tenant, pkg.id(), FileName.of("source.pdf"), MimeType.pdf(), 10,
                Checksum.sha256("a".repeat(64)), DocumentType.PDF, SensitivityLevel.HIGHLY_CONFIDENTIAL);
        Document other = Document.create(tenant, pkg.id(), FileName.of("other.pdf"), MimeType.pdf(), 10,
                Checksum.sha256("c".repeat(64)), DocumentType.PDF, SensitivityLevel.STANDARD);
        pkg.addDocument(other);
        pkg.addDocument(source);

        IntegrationConnection destination = IntegrationConnection.create(tenant, "Archive", null,
                IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY, IntegrationDirection.OUTBOUND,
                IntegrationProviderType.HTTP_API, IntegrationAuthenticationType.NONE, null,
                "https://example.test/derived", null, null, 3, 0, 24, 10, 10_000_000L, 100,
                IntegrationAcknowledgementMode.DESTINATION_RECEIPT, true, Instant.now());
        Artifact artifact = Artifact.create(tenant, source.id(), ArtifactType.REDACTED, MimeType.pdf(), 10,
                Checksum.sha256("b".repeat(64)));
        artifact.markAvailable(ArtifactStorageReference.local("derived/test.pdf"));
        RoutingRule rule = mock(RoutingRule.class);
        when(rule.id()).thenReturn(UUID.randomUUID());
        when(rule.destinationConnectionId()).thenReturn(destination.id());
        when(rule.destinationLabel()).thenReturn("Archive");
        DerivedDelivery delivery = DerivedDelivery.create(tenant, pkg.id(), source.id(), 1, rule,
                destination.configurationVersion(), List.of(1), artifact.id(), "part.pdf", Instant.now());

        DerivedDeliveryRepository deliveries = mock(DerivedDeliveryRepository.class);
        IntegrationConnectionRepository connections = mock(IntegrationConnectionRepository.class);
        ArtifactRepository artifacts = mock(ArtifactRepository.class);
        StoragePort storage = mock(StoragePort.class);
        DerivedDestinationDeliveryPort port = mock(DerivedDestinationDeliveryPort.class);
        PackageRepository packages = mock(PackageRepository.class);
        AuditService audit = mock(AuditService.class);
        when(deliveries.findByTenantIdAndId(tenant, delivery.id())).thenReturn(Optional.of(delivery));
        when(connections.findByTenantIdAndId(tenant, destination.id())).thenReturn(Optional.of(destination));
        when(artifacts.findByIdAndTenantId(artifact.id(), tenant)).thenReturn(Optional.of(artifact));
        when(storage.retrieve(any())).thenReturn(new ByteArrayInputStream("PDF".getBytes()));
        when(packages.findByIdAndTenantId(pkg.id(), tenant)).thenReturn(Optional.of(pkg));
        when(port.deliver(any(), any())).thenReturn(new DerivedDestinationDeliveryPort.DeliveryReceipt("r-1", "x"));

        new DerivedDeliveryService(deliveries, connections, artifacts, storage, port, audit, packages)
                .send(tenant, delivery.id(), ActorId.generate(), CorrelationId.generate());

        ArgumentCaptor<DerivedDestinationDeliveryPort.DeliveryRequest> sent =
                ArgumentCaptor.forClass(DerivedDestinationDeliveryPort.DeliveryRequest.class);
        verify(port).deliver(eq(destination), sent.capture());
        assertEquals("HIGHLY_CONFIDENTIAL", sent.getValue().metadata().get(DerivedDestinationDeliveryPort.SENSITIVITY_LEVEL));
        assertEquals("CONFIDENTIAL", sent.getValue().metadata().get(DerivedDestinationDeliveryPort.CASE_SENSITIVITY_LEVEL));
        // FILE_SHARE destinations get no level in the file (owner, 2026-11-20); LexaVault's own audit records it.
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit).recordSuccess(eq(tenant), any(), eq(AuditAction.DERIVED_DOCUMENT_DELIVERED), anyString(),
                anyString(), any(CorrelationId.class), detail.capture());
        assertTrue(detail.getValue().contains("sensitivityLevel=HIGHLY_CONFIDENTIAL"), detail.getValue());
        assertTrue(detail.getValue().contains("caseSensitivityLevel=CONFIDENTIAL"), detail.getValue());
    }
}
