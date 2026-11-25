package com.securedoc.platform.application.export.service;

import com.securedoc.platform.application.casefields.CaseFieldService;
import com.securedoc.platform.application.export.command.ExportPackageDocumentsCommand;
import com.securedoc.platform.application.export.command.ExportRedactedDocumentCommand;
import com.securedoc.platform.application.lifecycle.service.PackageLifecycleService;
import com.securedoc.platform.application.port.out.*;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.artifact.*;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.*;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.lifecycle.PackageExportRecord;
import com.securedoc.platform.domain.vo.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Task 0023 (A1, #178; B4, #200/#201): no original file name in the export audit detail or the export record. */
class ExportAuditNoFileNameTest {
    private static final String MARKER_NAME = "MARKER-borrower-7Q3.pdf";

    @Test
    void packageExportAuditAndRecordCarryIdsAndLevelsButNoFileName() {
        TenantId tenant = TenantId.generate();
        DocumentPackage pkg = DocumentPackage.createIntake(tenant, PackageType.API_SUBMISSION, null, null, null,
                ActorId.generate(), PackageMetadata.empty(), SensitivityLevel.CONFIDENTIAL);
        Instant now = Instant.now();
        Document document = Document.reconstitute(DocumentId.generate(), tenant, pkg.id(), FileName.of(MARKER_NAME),
                MimeType.pdf(), 10, Checksum.sha256("a".repeat(64)), DocumentType.PDF, now,
                DocumentStatus.PROCESSED, List.of(), now, SensitivityLevel.HIGHLY_CONFIDENTIAL);
        Artifact artifact = Artifact.create(tenant, document.id(), ArtifactType.REDACTED, MimeType.pdf(), 10,
                Checksum.sha256("b".repeat(64)));
        artifact.markAvailable(ArtifactStorageReference.local("redacted/test.pdf"));

        DocumentRepository documents = mock(DocumentRepository.class);
        PackageRepository packages = mock(PackageRepository.class);
        ArtifactRepository artifacts = mock(ArtifactRepository.class);
        DeliveryPort delivery = mock(DeliveryPort.class);
        DeliverySubmissionRepository submissions = mock(DeliverySubmissionRepository.class);
        PackageLifecycleService lifecycle = mock(PackageLifecycleService.class);
        AuditService audit = mock(AuditService.class);
        when(packages.findByIdAndTenantId(pkg.id(), tenant)).thenReturn(Optional.of(pkg));
        when(documents.findByPackageIdAndTenantId(pkg.id(), tenant)).thenReturn(List.of(document));
        when(artifacts.findByDocumentIdAndTenantId(document.id(), tenant)).thenReturn(List.of(artifact));
        when(lifecycle.nextExportCycle(pkg.id(), tenant)).thenReturn(1);
        when(delivery.describe(tenant)).thenReturn(new DeliveryPort.DeliveryDescriptor("local-folder", null, null));
        when(submissions.findByTenantIdAndPackageIdAndExportCycle(tenant, pkg.id(), 1)).thenReturn(Optional.empty());
        when(delivery.deliverBatch(any())).thenAnswer(call -> {
            DeliveryPort.DeliveryBatch batch = call.getArgument(0);
            DeliveryPort.DeliveryItem item = batch.items().get(0);
            return new DeliveryPort.BatchDeliveryReference("local-folder", null, List.of(new DeliveryPort.DeliveryReference(
                    item.documentId(), item.sourceDocumentId(), "local-folder", null, "local-folder:cycle-1/" + item.documentId())));
        });

        new PackageDocumentExportService(documents, packages, mock(CaseFieldService.class), artifacts,
                mock(StoragePort.class), delivery, submissions, lifecycle, audit)
                .execute(ExportPackageDocumentsCommand.of(pkg.id(), tenant, CorrelationId.generate()));

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit).recordSuccess(eq(tenant), any(), eq(AuditAction.DOCUMENT_EXPORTED),
                eq("Document"), eq(document.id().toString()), eq(pkg.id()), any(), detail.capture());
        assertFalse(detail.getValue().contains("MARKER"), detail.getValue());
        assertFalse(detail.getValue().contains("filename"), detail.getValue());
        assertTrue(detail.getValue().contains("sensitivityLevel=HIGHLY_CONFIDENTIAL"), detail.getValue());
        assertTrue(detail.getValue().contains("caseSensitivityLevel=CONFIDENTIAL"), detail.getValue());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PackageExportRecord.ExportedDocument>> exported = ArgumentCaptor.forClass(List.class);
        verify(lifecycle).recordSuccessfulExport(eq(pkg.id()), eq(tenant), eq(1), eq("local-folder"), exported.capture(), any());
        PackageExportRecord.ExportedDocument record = exported.getValue().get(0);
        assertEquals("document-" + document.id(), record.fileName());
        assertFalse(record.destinationLocation().contains("MARKER"), record.destinationLocation());
    }

    @Test
    void legacySingleDocumentExportAuditHasNoFileName() throws Exception {
        TenantId tenant = TenantId.generate();
        PackageId packageId = PackageId.generate();
        Instant now = Instant.now();
        Document document = Document.reconstitute(DocumentId.generate(), tenant, packageId, FileName.of(MARKER_NAME),
                MimeType.pdf(), 10, Checksum.sha256("a".repeat(64)), DocumentType.PDF, now,
                DocumentStatus.PROCESSED, List.of(), now, SensitivityLevel.STANDARD);
        Artifact artifact = Artifact.create(tenant, document.id(), ArtifactType.REDACTED, MimeType.pdf(), 10,
                Checksum.sha256("b".repeat(64)));
        artifact.markAvailable(ArtifactStorageReference.local("redacted/test.pdf"));
        DocumentRepository documents = mock(DocumentRepository.class);
        ArtifactRepository artifacts = mock(ArtifactRepository.class);
        StoragePort storage = mock(StoragePort.class);
        DeliveryPort delivery = mock(DeliveryPort.class);
        AuditService audit = mock(AuditService.class);
        when(documents.findByIdAndTenantId(document.id(), tenant)).thenReturn(Optional.of(document));
        when(artifacts.findByDocumentIdAndTenantId(document.id(), tenant)).thenReturn(List.of(artifact));
        when(storage.retrieve(any())).thenReturn(new ByteArrayInputStream("PDF".getBytes()));
        when(delivery.deliver(any(), any(), any(), any(), any(), anyLong(), any())).thenReturn(
                new DeliveryPort.DeliveryReference(document.id(), "doc-legacy", "local-folder", null, "x"));

        @SuppressWarnings("deprecation")
        DocumentExportService service = new DocumentExportService(documents, artifacts, storage, delivery, audit);
        service.execute(ExportRedactedDocumentCommand.of(document.id(), packageId, tenant, CorrelationId.generate()));

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit).recordSuccess(eq(tenant), any(), eq(AuditAction.DOCUMENT_EXPORTED),
                eq("Document"), eq(document.id().toString()), eq(packageId), any(), detail.capture());
        assertFalse(detail.getValue().contains("MARKER"), detail.getValue());
        assertFalse(detail.getValue().contains("filename"), detail.getValue());
    }
}
