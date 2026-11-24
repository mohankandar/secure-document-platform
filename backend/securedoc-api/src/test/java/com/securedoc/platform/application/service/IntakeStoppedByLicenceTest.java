package com.securedoc.platform.application.service;

import com.securedoc.platform.application.business.service.BusinessIdentityService;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.intake.command.CreatePackageCommand;
import com.securedoc.platform.application.intake.command.SubmitPackageCommand;
import com.securedoc.platform.application.intake.command.UploadContent;
import com.securedoc.platform.application.intake.command.UploadDocumentCommand;
import com.securedoc.platform.application.intake.external.ExternalIntakeService;
import com.securedoc.platform.application.intake.polling.PollingIntakeService;
import com.securedoc.platform.application.integration.IntegrationConnectionService;
import com.securedoc.platform.application.licensing.IntakeLicence;
import com.securedoc.platform.application.licensing.LicenseEnforcementException;
import com.securedoc.platform.application.lifecycle.command.ReprocessPackageCommand;
import com.securedoc.platform.application.lifecycle.service.PackageLifecycleService;
import com.securedoc.platform.application.lifecycle.service.ReprocessPackageService;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.ChecksumPort;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.DomainEventPublisher;
import com.securedoc.platform.application.port.out.FindingRepository;
import com.securedoc.platform.application.port.out.PackageProcessingDispatcher;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.PdfIntakeValidationPort;
import com.securedoc.platform.application.port.out.PollingRunRepository;
import com.securedoc.platform.application.port.out.PollingSourcePort;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.integration.IntegrationConnection;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.licensing.LicenseValidationStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Task 0057: with intake stopped by the licence, no entry makes, stores, changes or fetches anything. */
class IntakeStoppedByLicenceTest {

    private final LicenseEnforcementException over = new LicenseEnforcementException("LICENSE_EXPIRED", "over",
            LicenseValidationStatus.EXPIRED, "EXPIRED_GRACE_OVER");
    private final IntakeLicence stopped = mock(IntakeLicence.class);
    private final AuthenticatedPrincipal person = AuthenticatedPrincipal.of(ActorId.generate(), TenantId.generate(), "person", Set.of());
    private final PackageRepository packages = mock(PackageRepository.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);

    {
        when(stopped.refusal(any())).thenReturn(Optional.of(over));
        doThrow(over).when(stopped).requireIntake(any(), any(), any());
    }

    @Test
    void noCaseIsMade() {
        BusinessIdentityService identity = mock(BusinessIdentityService.class);
        CreatePackageService service = new CreatePackageService(mock(TenantContextService.class),
                mock(CapabilityEnforcementService.class), identity, stopped, packages, events, mock(AuditService.class));
        for (PackageType entry : PackageType.values()) {
            assertThrows(LicenseEnforcementException.class, () -> service.execute(CreatePackageCommand.of(entry,
                    Map.of("contractNumber", "C-1"), Map.of(), CorrelationId.generate()), person), entry.name());
        }
        verifyNoInteractions(packages, identity, events);
    }

    @Test
    void noDocumentIsAddedOrStored() {
        StoragePort storage = mock(StoragePort.class);
        UploadDocumentService service = new UploadDocumentService(mock(TenantContextService.class),
                mock(CapabilityEnforcementService.class), stopped, packages, documents, mock(ArtifactRepository.class),
                storage, mock(ChecksumPort.class), events, mock(AuditService.class), mock(PdfIntakeValidationPort.class));
        UploadContent content = mock(UploadContent.class);
        when(content.contentLength()).thenReturn(1L);
        assertThrows(LicenseEnforcementException.class, () -> service.execute(UploadDocumentCommand.of(PackageId.generate(),
                "a.pdf", "application/pdf", content, CorrelationId.generate()), person));
        verifyNoInteractions(packages, documents, storage, events);
    }

    @Test
    void noCaseIsSubmitted() {
        SubmitPackageService service = new SubmitPackageService(mock(TenantContextService.class),
                mock(CapabilityEnforcementService.class), stopped, packages, events, mock(AuditService.class));
        assertThrows(LicenseEnforcementException.class, () -> service.execute(SubmitPackageCommand.of(PackageId.generate(),
                CorrelationId.generate()), person));
        verifyNoInteractions(packages, events);
    }

    @Test
    void noFinishedCaseIsProcessedAgain() {
        PackageProcessingDispatcher dispatcher = mock(PackageProcessingDispatcher.class);
        ReprocessPackageService service = new ReprocessPackageService(packages, documents, mock(FindingRepository.class),
                mock(ArtifactRepository.class), mock(PackageLifecycleService.class), dispatcher, mock(AuditService.class), stopped);
        assertThrows(LicenseEnforcementException.class, () -> service.execute(new ReprocessPackageCommand(
                PackageId.generate(), person.tenantId(), person.actorId(), CorrelationId.generate())));
        verifyNoInteractions(packages, documents, dispatcher);
    }

    @Test
    void noSourceIsPolledAndNoRunIsRecorded() {
        IntegrationConnectionService connections = mock(IntegrationConnectionService.class);
        IntegrationConnection source = mock(IntegrationConnection.class);
        IntegrationConnectionId id = IntegrationConnectionId.generate();
        when(source.enabled()).thenReturn(true);
        when(source.pollingSource()).thenReturn(true);
        when(connections.get(person.tenantId(), id)).thenReturn(source);
        PollingRunRepository runs = mock(PollingRunRepository.class);
        PollingSourcePort port = mock(PollingSourcePort.class);
        ExternalIntakeService intake = mock(ExternalIntakeService.class);
        PollingIntakeService service = new PollingIntakeService(connections, runs, port, intake, mock(AuditService.class))
                .withLicence(stopped);
        assertTrue(service.runScheduled(person.tenantId(), id).isEmpty());
        assertThrows(LicenseEnforcementException.class, () -> service.runNow(person.tenantId(), id, person.actorId()));
        verifyNoInteractions(runs, port, intake);
    }

}
