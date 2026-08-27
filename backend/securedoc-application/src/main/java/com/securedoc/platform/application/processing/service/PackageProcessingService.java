package com.securedoc.platform.application.processing.service;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.port.in.StartPackageProcessingUseCase;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.DomainEventPublisher;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.processing.command.StartPackageProcessingCommand;
import com.securedoc.platform.application.processing.result.ProcessingWorkItem;
import com.securedoc.platform.application.processing.result.StartPackageProcessingResult;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.event.PackageProcessingStartedEvent;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Application service implementing package-level processing orchestration.
 * Transitions a submitted package to PROCESSING and identifies documents
 * that are ready for extraction work.
 *
 * <p>This service is invoked by the worker layer (system-initiated),
 * not directly by API endpoints.
 */
public final class PackageProcessingService implements StartPackageProcessingUseCase {

    /** System actor used for audit records in worker-initiated operations. */
    private static final ActorId SYSTEM_ACTOR = ActorId.of(UUID.fromString("00000000-0000-0000-0000-000000000000"));

    private final PackageRepository packageRepository;
    private final DocumentRepository documentRepository;
    private final DomainEventPublisher eventPublisher;
    private final AuditService auditService;

    public PackageProcessingService(PackageRepository packageRepository,
                                     DocumentRepository documentRepository,
                                     DomainEventPublisher eventPublisher,
                                     AuditService auditService) {
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.auditService = Objects.requireNonNull(auditService);
    }

    @Override
    public StartPackageProcessingResult execute(StartPackageProcessingCommand command) {
        Objects.requireNonNull(command, "Command must not be null");

        // 1. Load package by ID and tenant
        DocumentPackage documentPackage = packageRepository
                .findByIdAndTenantId(command.packageId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("DocumentPackage", command.packageId().toString()));

        // 2. Transition to PROCESSING via domain lifecycle (validates SUBMITTED state)
        documentPackage.startProcessing();

        // 3. Persist updated package
        packageRepository.save(documentPackage);

        // 4. Load documents for this package
        List<Document> documents = documentRepository.findByPackageIdAndTenantId(
                command.packageId(), command.tenantId());

        // 5. Identify documents ready for processing (UPLOADED status only)
        List<ProcessingWorkItem> workItems = documents.stream()
                .filter(doc -> doc.status() == DocumentStatus.UPLOADED)
                .map(doc -> ProcessingWorkItem.of(
                        doc.id(), command.packageId(), command.tenantId(), doc.status()))
                .toList();

        // 6. Publish domain event
        CorrelationId correlationId = command.correlationId();
        PackageProcessingStartedEvent event = PackageProcessingStartedEvent.of(
                command.packageId(), command.tenantId(), workItems.size(), correlationId);
        eventPublisher.publish(event);

        // 7. Audit
        auditService.recordSuccess(
                command.tenantId(),
                SYSTEM_ACTOR,
                AuditAction.PACKAGE_PROCESSING_STARTED,
                "DocumentPackage",
                command.packageId().toString(),
                correlationId,
                "Processing started: " + workItems.size() + " document(s) ready for extraction"
        );

        return StartPackageProcessingResult.of(
                command.packageId(), documentPackage.status(), workItems);
    }
}
