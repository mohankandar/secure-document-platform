package com.securedoc.platform.application.technical.service;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.in.GetTechnicalExceptionQuery;
import com.securedoc.platform.application.port.in.RetryTechnicalFailureUseCase;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.FindingRepository;
import com.securedoc.platform.application.port.out.PackageProcessingDispatcher;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.lifecycle.service.PackageLifecycleService;
import com.securedoc.platform.application.processing.command.TriggerPackageProcessingCommand;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.application.technical.command.RetryTechnicalFailureCommand;
import com.securedoc.platform.domain.artifact.ArtifactStatus;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.document.PackageStatus;

import java.util.Objects;

/**
 * Selectively retries a FAILED package only when its latest sanitized technical classification says retryable.
 * Successfully processed documents are retained; only failed documents are reset to the retained original.
 */
public final class RetryTechnicalFailureService implements RetryTechnicalFailureUseCase {
    private final GetTechnicalExceptionQuery technicalExceptionQuery;
    private final PackageRepository packageRepository;
    private final DocumentRepository documentRepository;
    private final FindingRepository findingRepository;
    private final ArtifactRepository artifactRepository;
    private final PackageLifecycleService lifecycleService;
    private final PackageProcessingDispatcher processingDispatcher;
    private final AuditService auditService;
    private final ProcessingIncidentService processingIncidentService;

    public RetryTechnicalFailureService(GetTechnicalExceptionQuery technicalExceptionQuery,
                                        PackageRepository packageRepository,
                                        DocumentRepository documentRepository,
                                        FindingRepository findingRepository,
                                        ArtifactRepository artifactRepository,
                                        PackageLifecycleService lifecycleService,
                                        PackageProcessingDispatcher processingDispatcher,
                                        AuditService auditService) {
        this(technicalExceptionQuery, packageRepository, documentRepository, findingRepository, artifactRepository,
                lifecycleService, processingDispatcher, auditService, ProcessingIncidentService.noop());
    }

    public RetryTechnicalFailureService(GetTechnicalExceptionQuery technicalExceptionQuery,
                                        PackageRepository packageRepository,
                                        DocumentRepository documentRepository,
                                        FindingRepository findingRepository,
                                        ArtifactRepository artifactRepository,
                                        PackageLifecycleService lifecycleService,
                                        PackageProcessingDispatcher processingDispatcher,
                                        AuditService auditService,
                                        ProcessingIncidentService processingIncidentService) {
        this.technicalExceptionQuery = Objects.requireNonNull(technicalExceptionQuery);
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.findingRepository = Objects.requireNonNull(findingRepository);
        this.artifactRepository = Objects.requireNonNull(artifactRepository);
        this.lifecycleService = Objects.requireNonNull(lifecycleService);
        this.processingDispatcher = Objects.requireNonNull(processingDispatcher);
        this.auditService = Objects.requireNonNull(auditService);
        this.processingIncidentService = Objects.requireNonNull(processingIncidentService);
    }

    @Override
    public void execute(RetryTechnicalFailureCommand command) {
        var detail = technicalExceptionQuery.execute(command.packageId(), command.tenantId());
        if (!detail.retryable()) {
            throw new ValidationException("This technical failure is not classified as retryable");
        }

        var pkg = packageRepository.findByIdAndTenantId(command.packageId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("DocumentPackage", command.packageId().toString()));
        if (pkg.status() != PackageStatus.FAILED) {
            throw new ValidationException("Only a failed package can be retried");
        }

        var documents = documentRepository.findByPackageIdAndTenantId(command.packageId(), command.tenantId());
        for (var document : documents) {
            if (document.status() != DocumentStatus.FAILED) continue;

            for (var artifact : artifactRepository.findByDocumentIdAndTenantId(document.id(), command.tenantId())) {
                if (artifact.artifactType() != ArtifactType.ORIGINAL && artifact.status() != ArtifactStatus.DELETED) {
                    lifecycleService.deleteArtifact(artifact);
                }
            }
            findingRepository.deleteByDocumentIdAndTenantId(document.id(), command.tenantId());
            try {
                document.prepareForTechnicalRetry();
            } catch (IllegalStateException ex) {
                throw new ValidationException(ex.getMessage());
            }
            documentRepository.save(document);
        }

        try {
            pkg.prepareForTechnicalRetry();
        } catch (IllegalStateException ex) {
            throw new ValidationException(ex.getMessage());
        }
        packageRepository.save(pkg);

        auditService.recordSuccess(command.tenantId(), command.actorId(), AuditAction.PACKAGE_TECHNICAL_RETRY_REQUESTED,
                "DocumentPackage", command.packageId().toString(), command.correlationId(),
                "Retry requested for a classified retryable technical failure");
        processingIncidentService.markRetryScheduled(command.packageId(), command.tenantId(), command.correlationId());

        processingDispatcher.dispatch(TriggerPackageProcessingCommand.of(
                command.packageId(), command.tenantId(), command.actorId(), command.correlationId()));
    }
}
