package com.securedoc.platform.application.processing.service;

import com.securedoc.platform.application.export.result.ExportPackageDocumentsResult;
import com.securedoc.platform.application.intelligence.service.DocumentIntelligenceExecutionService;
import com.securedoc.platform.application.lifecycle.service.PackageLifecycleService;
import com.securedoc.platform.application.port.in.*;
import com.securedoc.platform.application.port.out.*;
import com.securedoc.platform.application.processing.command.TriggerPackageProcessingCommand;
import com.securedoc.platform.application.resilience.SelectiveRetryExecutor;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.application.technical.service.ProcessingIncidentService;
import com.securedoc.platform.application.telemetry.ProductTelemetryService;
import com.securedoc.platform.domain.document.*;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.vo.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 0023 (C4, #181): derived routing throwing after the master export used to be a log line only. It is now
 * recorded (audit with a code + attention items) through the routing use case; the case still completes.
 */
class DerivedRoutingPostExportFailureTest {

    @Test
    void aRoutingFailureAfterTheMasterExportIsRecordedAndTheCaseStillCompletes() {
        TenantId tenant = TenantId.generate();
        DocumentPackage pkg = DocumentPackage.create(tenant, PackageType.MANUAL_UPLOAD, ActorId.generate(), PackageMetadata.empty());
        Document document = Document.reconstitute(DocumentId.generate(), tenant, pkg.id(), FileName.of("loan.pdf"),
                MimeType.pdf(), 100, Checksum.sha256("d".repeat(64)), DocumentType.PDF, Instant.now(),
                DocumentStatus.PROCESSED, List.of(), Instant.now());
        pkg.addDocument(document);
        pkg.submit();
        pkg.startProcessing();

        PackageRepository packages = mock(PackageRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        FindingRepository findings = mock(FindingRepository.class);
        ExportPackageDocumentsUseCase export = mock(ExportPackageDocumentsUseCase.class);
        ProcessDerivedRoutingUseCase routing = mock(ProcessDerivedRoutingUseCase.class);
        PackageLifecycleService lifecycle = mock(PackageLifecycleService.class);
        when(packages.findByIdAndTenantId(pkg.id(), tenant)).thenReturn(Optional.of(pkg));
        when(documents.findByPackageIdAndTenantId(pkg.id(), tenant)).thenReturn(List.of(document));
        when(findings.findByDocumentIdAndTenantId(any(), eq(tenant))).thenReturn(List.of());
        when(export.execute(any())).thenReturn(ExportPackageDocumentsResult.of(pkg.id(), 1, "local-folder", 3));
        doThrow(new IllegalStateException("routing")).when(routing).processPackage(eq(pkg.id()), eq(tenant), eq(3), any());

        TriggerPackageProcessingService service = new TriggerPackageProcessingService(packages, documents, findings,
                mock(ArtifactRepository.class), mock(ProcessingLockPort.class), mock(StartPackageProcessingUseCase.class),
                mock(StartDocumentProcessingUseCase.class), mock(ExtractDocumentTextUseCase.class),
                mock(DetectSensitiveDataUseCase.class), mock(RedactDocumentUseCase.class), export, routing,
                new SelectiveRetryExecutor(1, Duration.ZERO), lifecycle, mock(DocumentIntelligenceExecutionService.class),
                mock(AuditService.class), mock(ProcessingIncidentService.class), mock(ProductTelemetryService.class));

        service.execute(TriggerPackageProcessingCommand.of(pkg.id(), tenant, ActorId.generate(), CorrelationId.generate()));

        verify(routing).recordRoutingFailure(eq(pkg.id()), eq(tenant), eq(3), eq("DERIVED_ROUTING_POST_EXPORT_FAILED"), any());
        assertEquals(PackageStatus.COMPLETED, pkg.status());
    }
}
