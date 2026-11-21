package com.securedoc.platform.application.export.service;

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
import com.securedoc.platform.domain.vo.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Task 0021: the export batch carries the stored levels, on the first attempt and on every retry. */
class PackageDocumentExportSensitivityTest {

    @Test
    void everyAttemptCarriesTheStoredCaseAndDocumentLevels() {
        TenantId tenant = TenantId.generate();
        DocumentPackage pkg = DocumentPackage.createIntake(tenant, PackageType.API_SUBMISSION, null, null, null,
                ActorId.generate(), PackageMetadata.empty(), SensitivityLevel.CONFIDENTIAL);
        Instant now = Instant.now();
        Document document = Document.reconstitute(DocumentId.generate(), tenant, pkg.id(), FileName.of("redacted.pdf"),
                MimeType.pdf(), 10, Checksum.sha256("a".repeat(64)), DocumentType.PDF, now,
                DocumentStatus.PROCESSED, List.of(), now, SensitivityLevel.HIGHLY_CONFIDENTIAL);
        Artifact artifact = Artifact.create(tenant, document.id(), ArtifactType.REDACTED, MimeType.pdf(), 10,
                Checksum.sha256("b".repeat(64)));
        artifact.markAvailable(ArtifactStorageReference.local("redacted/test.pdf"));

        DocumentRepository documents = mock(DocumentRepository.class);
        PackageRepository packages = mock(PackageRepository.class);
        ArtifactRepository artifacts = mock(ArtifactRepository.class);
        StoragePort storage = mock(StoragePort.class);
        DeliveryPort delivery = mock(DeliveryPort.class);
        DeliverySubmissionRepository submissions = mock(DeliverySubmissionRepository.class);
        PackageLifecycleService lifecycle = mock(PackageLifecycleService.class);
        when(packages.findByIdAndTenantId(pkg.id(), tenant)).thenReturn(Optional.of(pkg));
        when(documents.findByPackageIdAndTenantId(pkg.id(), tenant)).thenReturn(List.of(document));
        when(artifacts.findByDocumentIdAndTenantId(document.id(), tenant)).thenReturn(List.of(artifact));
        when(storage.retrieve(artifact.storageReference())).thenReturn(new ByteArrayInputStream("PDF".getBytes()));
        when(lifecycle.nextExportCycle(pkg.id(), tenant)).thenReturn(1);
        when(delivery.describe(tenant)).thenReturn(new DeliveryPort.DeliveryDescriptor("sdv", IntegrationConnectionId.generate(), 1));
        when(submissions.findByTenantIdAndPackageIdAndExportCycle(tenant, pkg.id(), 1)).thenReturn(Optional.empty());
        when(delivery.deliverBatch(any())).thenThrow(new TechnicalDependencyException(
                TechnicalDependency.DELIVERY, TechnicalFailureCode.DELIVERY_IO, true, "SDV unavailable"));

        PackageDocumentExportService service = new PackageDocumentExportService(documents, packages,
                mock(BusinessRecordRepository.class), artifacts, storage, delivery, submissions, lifecycle,
                mock(AuditService.class));

        assertThrows(TechnicalDependencyException.class,
                () -> service.execute(ExportPackageDocumentsCommand.of(pkg.id(), tenant, CorrelationId.generate())));
        assertThrows(TechnicalDependencyException.class,
                () -> service.execute(ExportPackageDocumentsCommand.of(pkg.id(), tenant, CorrelationId.generate())));

        ArgumentCaptor<DeliveryPort.DeliveryBatch> batches = ArgumentCaptor.forClass(DeliveryPort.DeliveryBatch.class);
        verify(delivery, times(2)).deliverBatch(batches.capture());
        for (DeliveryPort.DeliveryBatch batch : batches.getAllValues()) {
            assertEquals(SensitivityLevel.CONFIDENTIAL, batch.caseSensitivityLevel());
            assertEquals(1, batch.items().size());
            assertEquals(SensitivityLevel.HIGHLY_CONFIDENTIAL, batch.items().get(0).sensitivityLevel());
        }
    }
}
