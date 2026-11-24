package com.securedoc.platform.application.redaction;

import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.intelligence.service.DocumentIntelligenceExecutionService;
import com.securedoc.platform.application.lifecycle.service.PackageLifecycleService;
import com.securedoc.platform.application.port.in.DetectSensitiveDataUseCase;
import com.securedoc.platform.application.port.in.ExportPackageDocumentsUseCase;
import com.securedoc.platform.application.port.in.ExtractDocumentTextUseCase;
import com.securedoc.platform.application.port.in.ProcessDerivedRoutingUseCase;
import com.securedoc.platform.application.port.in.RedactDocumentUseCase;
import com.securedoc.platform.application.port.in.StartDocumentProcessingUseCase;
import com.securedoc.platform.application.port.in.StartPackageProcessingUseCase;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.FindingRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.ProcessingFingerprintProvider;
import com.securedoc.platform.application.port.out.ProcessingIncidentRepository;
import com.securedoc.platform.application.port.out.ProcessingLockPort;
import com.securedoc.platform.application.processing.command.TriggerPackageProcessingCommand;
import com.securedoc.platform.application.processing.service.TriggerPackageProcessingService;
import com.securedoc.platform.application.resilience.SelectiveRetryExecutor;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.application.technical.service.ProcessingIncidentService;
import com.securedoc.platform.application.telemetry.ProductTelemetryService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.document.DocumentType;
import com.securedoc.platform.domain.document.PackageMetadata;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.processing.ProcessingIncident;
import com.securedoc.platform.domain.processing.ProcessingStage;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.FileName;
import com.securedoc.platform.domain.vo.MimeType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 0023 C3 (#137): a confirmed finding with no location used to fail the document silently
 * (no Support incident). It now reaches the standard processing stop with its own code.
 */
class UnlocatedFindingCreatesIncidentTest {

    @Test
    void unlocatedFindingStopCreatesASupportIncidentWithItsCode() {
        TenantId tenantId = TenantId.generate();
        DocumentPackage pkg = DocumentPackage.create(tenantId, PackageType.MANUAL_UPLOAD, ActorId.generate(),
                PackageMetadata.empty());
        Document placeholder = Document.create(tenantId, pkg.id(), FileName.of("a.pdf"), MimeType.pdf(), 64,
                Checksum.sha256("a"), DocumentType.PDF);
        pkg.addDocument(placeholder);
        pkg.submit();
        pkg.startProcessing();
        pkg.completeProcessing(); // PENDING_REVIEW: the reviewer confirmed an unlocated finding
        Document document = Document.reconstitute(DocumentId.generate(), tenantId, pkg.id(), FileName.of("a.pdf"),
                MimeType.pdf(), 64, Checksum.sha256("a"), DocumentType.PDF, Instant.now(), DocumentStatus.REDACTING,
                List.of(), Instant.now());

        PackageRepository packages = mock(PackageRepository.class);
        when(packages.findByIdAndTenantId(pkg.id(), tenantId)).thenReturn(Optional.of(pkg));
        DocumentRepository documents = mock(DocumentRepository.class);
        when(documents.findByPackageIdAndTenantId(pkg.id(), tenantId)).thenReturn(List.of(document));
        when(documents.findByIdAndTenantId(document.id(), tenantId)).thenReturn(Optional.of(document));
        FindingRepository findings = mock(FindingRepository.class);
        when(findings.findByDocumentIdAndTenantId(any(), any())).thenReturn(List.of());

        RedactDocumentUseCase redact = mock(RedactDocumentUseCase.class);
        // What DocumentRedactionService now throws for an unlocated confirmed finding.
        doAnswer(invocation -> {
            throw new TechnicalDependencyException(TechnicalDependency.REDACTION,
                    TechnicalFailureCode.REDACTION_LOCATION_MISSING, false, "Redaction location could not be determined");
        }).when(redact).execute(any());

        List<ProcessingIncident> incidents = new ArrayList<>();
        ProcessingIncidentRepository incidentRepository = mock(ProcessingIncidentRepository.class);
        doAnswer(invocation -> { incidents.add(invocation.getArgument(0)); return null; })
                .when(incidentRepository).save(any());
        ProcessingIncidentService incidentService = new ProcessingIncidentService(incidentRepository,
                ProcessingFingerprintProvider.empty());
        List<AuditRecord> audits = new ArrayList<>();

        TriggerPackageProcessingService service = new TriggerPackageProcessingService(packages, documents, findings,
                mock(ArtifactRepository.class), mock(ProcessingLockPort.class),
                mock(StartPackageProcessingUseCase.class), mock(StartDocumentProcessingUseCase.class),
                mock(ExtractDocumentTextUseCase.class), mock(DetectSensitiveDataUseCase.class), redact,
                mock(ExportPackageDocumentsUseCase.class), mock(ProcessDerivedRoutingUseCase.class),
                mock(SelectiveRetryExecutor.class), mock(PackageLifecycleService.class),
                mock(DocumentIntelligenceExecutionService.class), new AuditService(audits::add), incidentService,
                ProductTelemetryService.noop());

        service.execute(TriggerPackageProcessingCommand.of(pkg.id(), tenantId, ActorId.generate(),
                CorrelationId.generate()));

        assertEquals(1, incidents.size(), "The unlocated-finding stop must appear in the Support list");
        ProcessingIncident incident = incidents.get(0);
        assertEquals("REDACTION_LOCATION_MISSING", incident.reasonCode());
        assertEquals(ProcessingStage.REDACTION, incident.stage());
        assertEquals(document.id(), incident.documentId());
        assertEquals(PackageStatus.FAILED, pkg.status());
        assertTrue(audits.stream().anyMatch(a -> a.action() == AuditAction.DOCUMENT_PROCESSING_FAILED
                && a.detail().contains("code=LV-RED-001, detail=REDACTION_LOCATION_MISSING")));
    }
}
