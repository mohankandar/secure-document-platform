package com.securedoc.platform.application.export.service;

import com.securedoc.platform.application.casefields.CaseFieldService;
import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.export.command.ExportPackageDocumentsCommand;
import com.securedoc.platform.application.lifecycle.service.PackageLifecycleService;
import com.securedoc.platform.application.port.out.*;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.artifact.*;
import com.securedoc.platform.domain.document.*;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.vo.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PackageDocumentExportServiceTest {
    @Test
    void successfulDurableDeliveryIsTheOnlyPathThatStartsRetentionClock() {
        Fixture f = fixture();
        var ref = new DeliveryPort.DeliveryReference(f.document.id(), "source-doc-1", "document-store", "store-doc-1", "store://sub/doc");
        when(f.delivery.deliverBatch(any())).thenReturn(new DeliveryPort.BatchDeliveryReference("document-store", "store-sub-1", List.of(ref)));

        var result = f.service.execute(ExportPackageDocumentsCommand.of(f.pkg.id(), f.tenant, CorrelationId.generate()));

        assertEquals(1, result.exportCycle());
        verify(f.lifecycle).recordSuccessfulExport(eq(f.pkg.id()), eq(f.tenant), eq(1), eq("document-store"), anyList(), any());
    }

    @Test
    void technicalDeliveryFailureDoesNotStartRetentionClock() {
        Fixture f = fixture();
        when(f.delivery.deliverBatch(any())).thenThrow(new TechnicalDependencyException(
                TechnicalDependency.DELIVERY, TechnicalFailureCode.DELIVERY_IO, true, "Document store unavailable"));

        assertThrows(TechnicalDependencyException.class,
                () -> f.service.execute(ExportPackageDocumentsCommand.of(f.pkg.id(), f.tenant, CorrelationId.generate())));

        verify(f.lifecycle, never()).recordSuccessfulExport(any(), any(), anyInt(), anyString(), anyList(), any());
    }

    @Test
    void aLaterBatchDeliversOnlyItsOwnDocuments() {
        // Task 0104, step 3: the case's earlier document was delivered by batch 1; batch 2 brings one new version.
        Fixture f = fixture();
        Instant now = Instant.now();
        Document newVersion = Document.reconstitute(DocumentId.generate(), f.tenant, f.pkg.id(), FileName.of("v2.pdf"),
                MimeType.pdf(), 10, Checksum.sha256("c".repeat(64)), DocumentType.PDF, now,
                DocumentStatus.PROCESSED, List.of(), now);
        Artifact artifact = Artifact.create(f.tenant, newVersion.id(), ArtifactType.REDACTED, MimeType.pdf(), 10,
                Checksum.sha256("d".repeat(64)));
        artifact.markAvailable(ArtifactStorageReference.local("redacted/v2.pdf"));
        when(f.documents.findByPackageIdAndTenantId(f.pkg.id(), f.tenant)).thenReturn(List.of(f.document, newVersion));
        when(f.artifacts.findByDocumentIdAndTenantId(newVersion.id(), f.tenant)).thenReturn(List.of(artifact));
        when(f.storage.retrieve(artifact.storageReference())).thenReturn(new ByteArrayInputStream("PDF-CONTENT".getBytes()));
        when(f.lifecycle.currentBatchDocuments(f.pkg.id(), f.tenant)).thenReturn(Optional.of(Set.of(newVersion.id().value())));
        var ref = new DeliveryPort.DeliveryReference(newVersion.id(), "source-doc-2", "document-store", "store-doc-2", "store://sub/doc2");
        when(f.delivery.deliverBatch(any())).thenReturn(new DeliveryPort.BatchDeliveryReference("document-store", "store-sub-2", List.of(ref)));

        var result = f.service.execute(ExportPackageDocumentsCommand.of(f.pkg.id(), f.tenant, CorrelationId.generate()));

        assertEquals(1, result.exportedDocumentCount());
        verify(f.delivery).deliverBatch(argThat(batch -> batch.items().size() == 1
                && batch.items().getFirst().documentId().equals(newVersion.id())));
    }

    private Fixture fixture() {
        TenantId tenant = TenantId.generate();
        ActorId actor = ActorId.generate();
        DocumentPackage pkg = DocumentPackage.create(tenant, PackageType.MANUAL_UPLOAD, actor, PackageMetadata.empty());
        Instant now = Instant.now();
        Document document = Document.reconstitute(DocumentId.generate(), tenant, pkg.id(), FileName.of("redacted.pdf"),
                MimeType.pdf(), 10, Checksum.sha256("a".repeat(64)), DocumentType.PDF, now,
                DocumentStatus.PROCESSED, List.of(), now);
        Artifact artifact = Artifact.create(tenant, document.id(), ArtifactType.REDACTED, MimeType.pdf(), 10,
                Checksum.sha256("b".repeat(64)));
        artifact.markAvailable(ArtifactStorageReference.local("redacted/test.pdf"));

        DocumentRepository documents = mock(DocumentRepository.class);
        PackageRepository packages = mock(PackageRepository.class);
        CaseFieldService businesses = mock(CaseFieldService.class);
        ArtifactRepository artifacts = mock(ArtifactRepository.class);
        StoragePort storage = mock(StoragePort.class);
        DeliveryPort delivery = mock(DeliveryPort.class);
        DeliverySubmissionRepository submissions = mock(DeliverySubmissionRepository.class);
        PackageLifecycleService lifecycle = mock(PackageLifecycleService.class);
        AuditService audit = mock(AuditService.class);

        when(packages.findByIdAndTenantId(pkg.id(), tenant)).thenReturn(Optional.of(pkg));
        when(documents.findByPackageIdAndTenantId(pkg.id(), tenant)).thenReturn(List.of(document));
        when(artifacts.findByDocumentIdAndTenantId(document.id(), tenant)).thenReturn(List.of(artifact));
        when(storage.retrieve(artifact.storageReference())).thenReturn(new ByteArrayInputStream("PDF-CONTENT".getBytes()));
        when(lifecycle.nextExportCycle(pkg.id(), tenant)).thenReturn(1);
        when(delivery.describe(tenant)).thenReturn(new DeliveryPort.DeliveryDescriptor("document-store", IntegrationConnectionId.generate(), 1));
        when(submissions.findByTenantIdAndPackageIdAndExportCycle(tenant, pkg.id(), 1)).thenReturn(Optional.empty());

        PackageDocumentExportService service = new PackageDocumentExportService(documents, packages, businesses, artifacts,
                storage, delivery, submissions, lifecycle, audit);
        return new Fixture(tenant, pkg, document, service, delivery, lifecycle, documents, artifacts, storage);
    }

    private record Fixture(TenantId tenant, DocumentPackage pkg, Document document,
                           PackageDocumentExportService service, DeliveryPort delivery,
                           PackageLifecycleService lifecycle, DocumentRepository documents,
                           ArtifactRepository artifacts, StoragePort storage) { }
}
