package com.securedoc.platform.application.lifecycle.service;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.lifecycle.command.ReprocessPackageCommand;
import com.securedoc.platform.application.lifecycle.result.ReprocessPackageResult;
import com.securedoc.platform.application.licensing.TenantLicensingService;
import com.securedoc.platform.application.port.in.ReprocessPackageUseCase;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.FindingRepository;
import com.securedoc.platform.application.port.out.PackageProcessingDispatcher;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.processing.command.TriggerPackageProcessingCommand;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactStatus;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.PackageStatus;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Starts another processing/export cycle from the retained original without creating a new intake batch. */
public final class ReprocessPackageService implements ReprocessPackageUseCase {
    private final PackageRepository packageRepository;
    private final DocumentRepository documentRepository;
    private final FindingRepository findingRepository;
    private final ArtifactRepository artifactRepository;
    private final PackageLifecycleService lifecycleService;
    private final PackageProcessingDispatcher packageProcessingDispatcher;
    private final AuditService auditService;
    private final TenantLicensingService tenantLicensingService;

    public ReprocessPackageService(PackageRepository packageRepository,
                                   DocumentRepository documentRepository,
                                   FindingRepository findingRepository,
                                   ArtifactRepository artifactRepository,
                                   PackageLifecycleService lifecycleService,
                                   PackageProcessingDispatcher packageProcessingDispatcher,
                                   AuditService auditService,
                                   TenantLicensingService tenantLicensingService) {
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.findingRepository = Objects.requireNonNull(findingRepository);
        this.artifactRepository = Objects.requireNonNull(artifactRepository);
        this.lifecycleService = Objects.requireNonNull(lifecycleService);
        this.packageProcessingDispatcher = Objects.requireNonNull(packageProcessingDispatcher);
        this.auditService = Objects.requireNonNull(auditService);
        this.tenantLicensingService = Objects.requireNonNull(tenantLicensingService);
    }

    @Override
    public ReprocessPackageResult execute(ReprocessPackageCommand command) {
        tenantLicensingService.requireDocumentProcessing(command.tenantId(), command.actorId(), command.correlationId());
        DocumentPackage pkg = packageRepository.findByIdAndTenantId(command.packageId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("DocumentPackage", command.packageId().toString()));
        if (pkg.status() != PackageStatus.COMPLETED) {
            throw new ValidationException("Only a completed package can be reprocessed");
        }
        if (!lifecycleService.canReprocess(command.packageId(), command.tenantId())) {
            throw new ValidationException("Reprocessing is unavailable because the original expired, was purged, or the maximum export cycles were reached");
        }
        int nextCycle = lifecycleService.nextExportCycle(command.packageId(), command.tenantId());
        List<Document> documents = documentRepository.findByPackageIdAndTenantId(command.packageId(), command.tenantId());
        if (documents.isEmpty()) throw new ValidationException("Package has no documents to reprocess");

        // Remove transient outputs/findings from the prior run. The ORIGINAL is intentionally retained.
        for (Document document : documents) {
            for (Artifact artifact : artifactRepository.findByDocumentIdAndTenantId(document.id(), command.tenantId())) {
                if (artifact.artifactType() != ArtifactType.ORIGINAL && artifact.status() != ArtifactStatus.DELETED) {
                    lifecycleService.deleteArtifact(artifact);
                }
            }
            findingRepository.deleteByDocumentIdAndTenantId(document.id(), command.tenantId());
            try {
                document.prepareForReprocessing();
            } catch (IllegalStateException e) {
                throw new ValidationException(e.getMessage());
            }
            documentRepository.save(document);
        }

        lifecycleService.clearFinalApproval(command.packageId(), command.tenantId(), Instant.now());
        try {
            pkg.prepareForReprocessing();
        } catch (IllegalStateException e) {
            throw new ValidationException(e.getMessage());
        }
        packageRepository.save(pkg);
        auditService.recordSuccess(command.tenantId(), command.actorId(), AuditAction.PACKAGE_REPROCESS_REQUESTED,
                "DocumentPackage", command.packageId().toString(), command.correlationId(),
                "Package reprocessing requested: nextExportCycle=" + nextCycle);

        // Reprocessing uses the same asynchronous post-commit processing contract as normal intake.
        // The request returns promptly with the durable SUBMITTED state; the worker moves it to PROCESSING.
        packageProcessingDispatcher.dispatch(TriggerPackageProcessingCommand.freshCycle(
                command.packageId(), command.tenantId(), command.actorId(), command.correlationId()));
        return new ReprocessPackageResult(command.packageId(), pkg.status(), nextCycle);
    }
}
