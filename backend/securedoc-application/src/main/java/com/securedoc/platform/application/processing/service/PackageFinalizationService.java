package com.securedoc.platform.application.processing.service;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.in.FinalizePackageUseCase;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.DomainEventPublisher;
import com.securedoc.platform.application.port.out.FindingRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.processing.command.FinalizePackageCommand;
import com.securedoc.platform.application.processing.result.FinalizePackageResult;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.finding.FindingStatus;

import java.util.List;
import java.util.Objects;

/**
 * Application service for safely finalizing a package.
 * Validates all documents are PROCESSED and all findings are resolved
 * before approving the package for vault registration.
 *
 * <p>This service bridges the gap between review completion (task approved)
 * and package approval (package ready for vault).
 */
public final class PackageFinalizationService implements FinalizePackageUseCase {

    private final PackageRepository packageRepository;
    private final DocumentRepository documentRepository;
    private final FindingRepository findingRepository;
    private final DomainEventPublisher eventPublisher;
    private final AuditService auditService;

    public PackageFinalizationService(PackageRepository packageRepository,
                                       DocumentRepository documentRepository,
                                       FindingRepository findingRepository,
                                       DomainEventPublisher eventPublisher,
                                       AuditService auditService) {
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.findingRepository = Objects.requireNonNull(findingRepository);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.auditService = Objects.requireNonNull(auditService);
    }

    @Override
    public FinalizePackageResult execute(FinalizePackageCommand command) {
        Objects.requireNonNull(command, "Command must not be null");

        // 1. Load package
        DocumentPackage pkg = packageRepository
                .findByIdAndTenantId(command.packageId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("DocumentPackage", command.packageId().toString()));

        // 2. Package must be in IN_REVIEW or PENDING_REVIEW status
        if (pkg.status() != PackageStatus.IN_REVIEW && pkg.status() != PackageStatus.PENDING_REVIEW) {
            throw new ValidationException(
                    "Package must be in IN_REVIEW or PENDING_REVIEW status to finalize. Current: " + pkg.status());
        }

        // 3. Load all documents
        List<Document> documents = documentRepository
                .findByPackageIdAndTenantId(command.packageId(), command.tenantId());

        if (documents.isEmpty()) {
            throw new ValidationException("Package has no documents");
        }

        // 4. Verify all documents are PROCESSED
        for (Document doc : documents) {
            if (doc.status() != DocumentStatus.PROCESSED) {
                throw new ValidationException(
                        "Cannot finalize: document " + doc.id() + " is in status " + doc.status()
                                + ". All documents must be PROCESSED.");
            }
        }

        // 5. Verify all findings are in safe terminal states
        for (Document doc : documents) {
            List<Finding> findings = findingRepository
                    .findByDocumentIdAndTenantId(doc.id(), command.tenantId());

            for (Finding finding : findings) {
                if (finding.status() == FindingStatus.DETECTED) {
                    throw new ValidationException(
                            "Cannot finalize: finding " + finding.id() + " has not been reviewed");
                }
                if (finding.status() == FindingStatus.CONFIRMED) {
                    throw new ValidationException(
                            "Cannot finalize: finding " + finding.id()
                                    + " is confirmed but not yet redacted");
                }
                // REDACTED, DISMISSED, OVERRIDDEN are all safe terminal states
            }
        }

        // 6. Approve the package
        // If package is PENDING_REVIEW, transition through IN_REVIEW first
        if (pkg.status() == PackageStatus.PENDING_REVIEW) {
            pkg.startReview();
        }
        pkg.approve();

        packageRepository.save(pkg);

        // 7. Audit
        auditService.recordSuccess(command.tenantId(), command.actorId(),
                AuditAction.PACKAGE_APPROVED, "DocumentPackage", command.packageId().toString(),
                command.correlationId(),
                "Package finalized and approved: " + documents.size() + " document(s) verified safe");

        return FinalizePackageResult.of(command.packageId(), pkg.status());
    }
}
