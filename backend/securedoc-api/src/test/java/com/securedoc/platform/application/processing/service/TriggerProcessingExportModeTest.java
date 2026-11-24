package com.securedoc.platform.application.processing.service;

import com.securedoc.platform.application.export.result.ExportPackageDocumentsResult;
import com.securedoc.platform.application.intelligence.service.DocumentIntelligenceExecutionService;
import com.securedoc.platform.application.licensing.TenantLicensingService;
import com.securedoc.platform.application.lifecycle.service.PackageLifecycleService;
import com.securedoc.platform.application.pipeline.ExportJobScheduler;
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

/** Task 0027: where processing hands a finished case to export, by the export-mode setting. */
class TriggerProcessingExportModeTest {
    private final TenantId tenant = TenantId.generate();
    private final PackageRepository packages = mock(PackageRepository.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final FindingRepository findings = mock(FindingRepository.class);
    private final ExportPackageDocumentsUseCase export = mock(ExportPackageDocumentsUseCase.class);
    private final ProcessDerivedRoutingUseCase routing = mock(ProcessDerivedRoutingUseCase.class);
    private final PackageLifecycleService lifecycle = mock(PackageLifecycleService.class);
    private final ProcessingIncidentService incidents = mock(ProcessingIncidentService.class);

    @Test
    void withExportJobsOnAFinishedCaseIsQueuedAndStaysProcessingAsReadyForExport() {
        DocumentPackage pkg = finishedCase();
        ExportJobScheduler scheduler = mock(ExportJobScheduler.class);
        when(scheduler.jobMode()).thenReturn(true);
        when(lifecycle.nextExportCycle(pkg.id(), tenant)).thenReturn(4);

        service(scheduler).execute(TriggerPackageProcessingCommand.of(pkg.id(), tenant, ActorId.generate(),
                CorrelationId.generate()));

        verify(scheduler).queueCaseExport(eq(tenant), eq(pkg.id()), eq(4), any());
        verifyNoInteractions(export, routing);
        assertEquals(PackageStatus.PROCESSING, pkg.status(), "not completed, not failed: Ready for export");
    }

    @Test
    void withExportJobsOnACaseThatCanNotBeQueuedFailsAsAnExportFailureWouldToday() {
        DocumentPackage pkg = finishedCase();
        ExportJobScheduler scheduler = mock(ExportJobScheduler.class);
        when(scheduler.jobMode()).thenReturn(true);
        doThrow(new IllegalStateException("database")).when(scheduler).queueCaseExport(any(), any(), anyInt(), any());

        service(scheduler).execute(TriggerPackageProcessingCommand.of(pkg.id(), tenant, ActorId.generate(),
                CorrelationId.generate()));

        assertEquals(PackageStatus.FAILED, pkg.status());
        verify(incidents).recordFailure(eq(tenant), eq(pkg.id()), isNull(), any(), any(), any(), anyString(), any());
    }

    @Test
    void inlineExportsAtTheEndOfProcessingAsToday() {
        DocumentPackage pkg = finishedCase();
        when(export.execute(any())).thenReturn(ExportPackageDocumentsResult.of(pkg.id(), 1, "local-folder", 1));

        service(ExportJobScheduler.INLINE).execute(TriggerPackageProcessingCommand.of(pkg.id(), tenant,
                ActorId.generate(), CorrelationId.generate()));

        verify(export).execute(any());
        verify(routing).processPackage(eq(pkg.id()), eq(tenant), eq(1), any());
        assertEquals(PackageStatus.COMPLETED, pkg.status());
    }

    /** Task 0052: after a switch back to inline, a case its export job has is left to that job. */
    @Test
    void inlineLeavesACaseThatAnExportJobHasToThatJob() {
        DocumentPackage pkg = finishedCase();
        ExportJobScheduler scheduler = mock(ExportJobScheduler.class);
        when(lifecycle.nextExportCycle(pkg.id(), tenant)).thenReturn(2);
        when(scheduler.jobHasCase(tenant, pkg.id(), 2)).thenReturn(true);

        service(scheduler).execute(TriggerPackageProcessingCommand.of(pkg.id(), tenant, ActorId.generate(),
                CorrelationId.generate()));

        verifyNoInteractions(export, routing);
        verify(scheduler, never()).queueCaseExport(any(), any(), anyInt(), any());
        assertEquals(PackageStatus.PROCESSING, pkg.status());
    }

    /** Task 0052: the job finished the case between the trigger's first read and its check. */
    @Test
    void inlineDoesNotExportACaseWhoseJobFinishedItMeanwhile() {
        DocumentPackage pkg = finishedCase();
        ExportJobScheduler scheduler = mock(ExportJobScheduler.class);
        when(scheduler.jobHasCase(any(), any(), anyInt())).thenAnswer(i -> {
            pkg.completeSuccessfully(); // the job's last write; its job is now done, for the run before
            return false;
        });

        service(scheduler).execute(TriggerPackageProcessingCommand.of(pkg.id(), tenant, ActorId.generate(),
                CorrelationId.generate()));

        verifyNoInteractions(export, routing);
        assertEquals(PackageStatus.COMPLETED, pkg.status());
    }

    private DocumentPackage finishedCase() {
        DocumentPackage pkg = DocumentPackage.create(tenant, PackageType.MANUAL_UPLOAD, ActorId.generate(),
                PackageMetadata.empty());
        Document document = Document.reconstitute(DocumentId.generate(), tenant, pkg.id(), FileName.of("loan.pdf"),
                MimeType.pdf(), 100, Checksum.sha256("d".repeat(64)), DocumentType.PDF, Instant.now(),
                DocumentStatus.PROCESSED, List.of(), Instant.now());
        pkg.addDocument(document);
        pkg.submit();
        pkg.startProcessing();
        when(packages.findByIdAndTenantId(pkg.id(), tenant)).thenReturn(Optional.of(pkg));
        when(documents.findByPackageIdAndTenantId(pkg.id(), tenant)).thenReturn(List.of(document));
        when(findings.findByDocumentIdAndTenantId(any(), eq(tenant))).thenReturn(List.of());
        return pkg;
    }

    private TriggerPackageProcessingService service(ExportJobScheduler scheduler) {
        return new TriggerPackageProcessingService(packages, documents, findings,
                mock(ArtifactRepository.class), mock(ProcessingLockPort.class), mock(StartPackageProcessingUseCase.class),
                mock(StartDocumentProcessingUseCase.class), mock(ExtractDocumentTextUseCase.class),
                mock(DetectSensitiveDataUseCase.class), mock(RedactDocumentUseCase.class), export, routing,
                new SelectiveRetryExecutor(1, Duration.ZERO), lifecycle, mock(DocumentIntelligenceExecutionService.class),
                mock(AuditService.class), incidents, mock(TenantLicensingService.class),
                mock(ProductTelemetryService.class), scheduler);
    }
}
