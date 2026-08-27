package com.securedoc.platform.application.review.service;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.in.ConfirmFindingUseCase;
import com.securedoc.platform.application.port.in.DismissFindingUseCase;
import com.securedoc.platform.application.port.in.UpdateFindingBoundingBoxUseCase;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.FindingRepository;
import com.securedoc.platform.application.port.out.ReviewTaskRepository;
import com.securedoc.platform.application.review.command.ConfirmFindingCommand;
import com.securedoc.platform.application.review.command.DismissFindingCommand;
import com.securedoc.platform.application.review.command.UpdateFindingBoundingBoxCommand;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.finding.FindingLocation;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.FindingId;
import com.securedoc.platform.domain.identity.ReviewTaskId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.review.ReviewStatus;
import com.securedoc.platform.domain.review.ReviewTask;

import java.util.Objects;

/**
 * Application service for finding-level review actions (confirm, dismiss, update bounding box).
 * All actions require an active IN_PROGRESS review task with the actor as assigned reviewer.
 *
 * <p>Never stores raw sensitive values in audit/outbox.
 */
public final class FindingReviewService implements ConfirmFindingUseCase,
        DismissFindingUseCase, UpdateFindingBoundingBoxUseCase {

    private final FindingRepository findingRepository;
    private final ReviewTaskRepository reviewTaskRepository;
    private final DocumentRepository documentRepository;
    private final AuditService auditService;

    public FindingReviewService(FindingRepository findingRepository,
                                 ReviewTaskRepository reviewTaskRepository,
                                 DocumentRepository documentRepository,
                                 AuditService auditService) {
        this.findingRepository = Objects.requireNonNull(findingRepository);
        this.reviewTaskRepository = Objects.requireNonNull(reviewTaskRepository);
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.auditService = Objects.requireNonNull(auditService);
    }

    @Override
    public void execute(ConfirmFindingCommand command) {
        Objects.requireNonNull(command, "Command must not be null");

        validateReviewContext(command.reviewTaskId(), command.tenantId(), command.actorId(), command.findingId());

        Finding finding = findingRepository
                .findByIdAndTenantId(command.findingId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("Finding", command.findingId().toString()));

        String safeReason = ReviewTextSanitizer.sanitizeReason(command.reason());
        finding.confirm(command.actorId(), safeReason);
        findingRepository.save(finding);

        auditService.recordSuccess(command.tenantId(), command.actorId(),
                AuditAction.FINDING_CONFIRMED, "Finding", command.findingId().toString(),
                command.correlationId(), "Finding confirmed: type=" + finding.entityType());
    }

    @Override
    public void execute(DismissFindingCommand command) {
        Objects.requireNonNull(command, "Command must not be null");

        validateReviewContext(command.reviewTaskId(), command.tenantId(), command.actorId(), command.findingId());

        Finding finding = findingRepository
                .findByIdAndTenantId(command.findingId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("Finding", command.findingId().toString()));

        String safeReason = ReviewTextSanitizer.sanitizeReason(command.reason());
        finding.dismiss(command.actorId(), safeReason);
        findingRepository.save(finding);

        auditService.recordSuccess(command.tenantId(), command.actorId(),
                AuditAction.FINDING_DISMISSED, "Finding", command.findingId().toString(),
                command.correlationId(), "Finding dismissed: type=" + finding.entityType());
    }

    @Override
    public void execute(UpdateFindingBoundingBoxCommand command) {
        Objects.requireNonNull(command, "Command must not be null");

        validateReviewContext(command.reviewTaskId(), command.tenantId(), command.actorId(), command.findingId());

        Finding finding = findingRepository
                .findByIdAndTenantId(command.findingId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("Finding", command.findingId().toString()));

        FindingLocation currentLocation = finding.location();
        FindingLocation.BoundingBox newBox = FindingLocation.BoundingBox.of(
                command.left(), command.top(), command.width(), command.height());
        FindingLocation updatedLocation = FindingLocation.withBounds(
                currentLocation.pageNumber(), currentLocation.startOffset(),
                currentLocation.endOffset(), newBox);

        finding.updateLocation(updatedLocation);
        findingRepository.save(finding);

        auditService.recordSuccess(command.tenantId(), command.actorId(),
                AuditAction.FINDING_BOUNDING_BOX_UPDATED, "Finding", command.findingId().toString(),
                command.correlationId(), "Bounding box updated for finding on page " + currentLocation.pageNumber());
    }

    /**
     * Validates that the actor has an active IN_PROGRESS review task that covers the finding's document.
     */
    private void validateReviewContext(ReviewTaskId reviewTaskId, TenantId tenantId,
                                       ActorId actorId, FindingId findingId) {
        // 1. Load review task
        ReviewTask task = reviewTaskRepository
                .findByIdAndTenantId(reviewTaskId, tenantId)
                .orElseThrow(() -> new NotFoundException("ReviewTask", reviewTaskId.toString()));

        // 2. Verify task is IN_PROGRESS
        if (task.status() != ReviewStatus.IN_PROGRESS) {
            throw new ValidationException("Review task is not in IN_PROGRESS status");
        }

        // 3. Verify actor is assigned reviewer
        if (task.assignedTo().isEmpty() || !task.assignedTo().get().equals(actorId)) {
            throw new ValidationException("Only the assigned reviewer can perform finding actions");
        }

        // 4. Load finding and verify it belongs to the same package
        Finding finding = findingRepository
                .findByIdAndTenantId(findingId, tenantId)
                .orElseThrow(() -> new NotFoundException("Finding", findingId.toString()));

        Document document = documentRepository
                .findByIdAndTenantId(finding.documentId(), tenantId)
                .orElseThrow(() -> new NotFoundException("Document", finding.documentId().toString()));

        if (!document.packageId().equals(task.packageId())) {
            throw new ValidationException("Finding does not belong to the review task's package");
        }
    }
}
