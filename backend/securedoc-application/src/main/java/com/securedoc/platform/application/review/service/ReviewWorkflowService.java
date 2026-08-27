package com.securedoc.platform.application.review.service;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.in.AssignReviewTaskUseCase;
import com.securedoc.platform.application.port.in.CompleteReviewTaskUseCase;
import com.securedoc.platform.application.port.in.CreateReviewTaskUseCase;
import com.securedoc.platform.application.port.in.StartReviewTaskUseCase;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.DomainEventPublisher;
import com.securedoc.platform.application.port.out.FindingRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.ReviewTaskRepository;
import com.securedoc.platform.application.review.command.AssignReviewTaskCommand;
import com.securedoc.platform.application.review.command.CompleteReviewTaskCommand;
import com.securedoc.platform.application.review.command.CreateReviewTaskCommand;
import com.securedoc.platform.application.review.command.StartReviewTaskCommand;
import com.securedoc.platform.application.review.result.ReviewTaskResult;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.event.ReviewCompletedEvent;
import com.securedoc.platform.domain.event.ReviewTaskCreatedEvent;
import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.finding.FindingStatus;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.review.ReviewStatus;
import com.securedoc.platform.domain.review.ReviewTask;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Application service implementing review task lifecycle operations.
 *
 * <p>Never stores raw sensitive values in audit/outbox/events.
 * Comments/reasons are sanitized before passing to domain.
 */
public final class ReviewWorkflowService implements CreateReviewTaskUseCase,
        AssignReviewTaskUseCase, StartReviewTaskUseCase, CompleteReviewTaskUseCase {

    private static final ActorId SYSTEM_ACTOR = ActorId.of(UUID.fromString("00000000-0000-0000-0000-000000000000"));

    private final ReviewTaskRepository reviewTaskRepository;
    private final PackageRepository packageRepository;
    private final DocumentRepository documentRepository;
    private final FindingRepository findingRepository;
    private final DomainEventPublisher eventPublisher;
    private final AuditService auditService;

    public ReviewWorkflowService(ReviewTaskRepository reviewTaskRepository,
                                  PackageRepository packageRepository,
                                  DocumentRepository documentRepository,
                                  FindingRepository findingRepository,
                                  DomainEventPublisher eventPublisher,
                                  AuditService auditService) {
        this.reviewTaskRepository = Objects.requireNonNull(reviewTaskRepository);
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.findingRepository = Objects.requireNonNull(findingRepository);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.auditService = Objects.requireNonNull(auditService);
    }

    @Override
    public ReviewTaskResult execute(CreateReviewTaskCommand command) {
        Objects.requireNonNull(command, "Command must not be null");

        DocumentPackage pkg = packageRepository
                .findByIdAndTenantId(command.packageId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("DocumentPackage", command.packageId().toString()));

        if (pkg.status() != PackageStatus.PENDING_REVIEW) {
            throw new ValidationException("Package is not in PENDING_REVIEW status");
        }

        reviewTaskRepository.findOpenByPackageIdAndTenantId(command.packageId(), command.tenantId())
                .ifPresent(existing -> {
                    throw new ValidationException("An open review task already exists for this package");
                });

        ReviewTask task = ReviewTask.create(command.tenantId(), command.packageId());

        pkg.startReview();

        reviewTaskRepository.save(task);
        packageRepository.save(pkg);

        ReviewTaskCreatedEvent event = ReviewTaskCreatedEvent.of(
                task.id(), command.packageId(), command.tenantId(), command.correlationId());
        eventPublisher.publish(event);

        auditService.recordSuccess(command.tenantId(), SYSTEM_ACTOR,
                AuditAction.REVIEW_TASK_CREATED, "ReviewTask", task.id().toString(),
                command.correlationId(), "Review task created for package=" + command.packageId());

        return ReviewTaskResult.from(task);
    }

    @Override
    public ReviewTaskResult execute(AssignReviewTaskCommand command) {
        Objects.requireNonNull(command, "Command must not be null");

        ReviewTask task = reviewTaskRepository
                .findByIdAndTenantId(command.reviewTaskId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("ReviewTask", command.reviewTaskId().toString()));

        task.assign(command.reviewerId());
        reviewTaskRepository.save(task);

        auditService.recordSuccess(command.tenantId(), command.reviewerId(),
                AuditAction.REVIEW_TASK_ASSIGNED, "ReviewTask", task.id().toString(),
                command.correlationId(), "Review task assigned");

        return ReviewTaskResult.from(task);
    }

    @Override
    public ReviewTaskResult execute(StartReviewTaskCommand command) {
        Objects.requireNonNull(command, "Command must not be null");

        ReviewTask task = reviewTaskRepository
                .findByIdAndTenantId(command.reviewTaskId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("ReviewTask", command.reviewTaskId().toString()));

        task.start(command.reviewerId());
        reviewTaskRepository.save(task);

        auditService.recordSuccess(command.tenantId(), command.reviewerId(),
                AuditAction.REVIEW_STARTED, "ReviewTask", task.id().toString(),
                command.correlationId(), "Review started");

        return ReviewTaskResult.from(task);
    }

    @Override
    public ReviewTaskResult execute(CompleteReviewTaskCommand command) {
        Objects.requireNonNull(command, "Command must not be null");

        ReviewTask task = reviewTaskRepository
                .findByIdAndTenantId(command.reviewTaskId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("ReviewTask", command.reviewTaskId().toString()));

        // Validate all findings are resolved before allowing completion
        validateFindingsResolved(task);

        // Sanitize comment before passing to domain
        String safeComment = ReviewTextSanitizer.sanitizeComment(command.comment());

        // Approve the review task (IN_PROGRESS → APPROVED) - enforces assigned reviewer check
        task.approve(command.reviewerId(), safeComment);
        reviewTaskRepository.save(task);

        // Publish event
        ReviewCompletedEvent event = ReviewCompletedEvent.of(
                task.id(), task.packageId(), command.tenantId(),
                command.reviewerId(), ReviewStatus.APPROVED, command.correlationId());
        eventPublisher.publish(event);

        auditService.recordSuccess(command.tenantId(), command.reviewerId(),
                AuditAction.REVIEW_APPROVED, "ReviewTask", task.id().toString(),
                command.correlationId(), "Review approved for package=" + task.packageId());

        return ReviewTaskResult.from(task);
    }

    /**
     * Validates that no findings remain in an unresolved state for the package.
     * Blocks review completion if:
     * - Any finding is still DETECTED (not yet reviewed)
     * - Any CONFIRMED finding lacks a bounding box (required for redaction)
     */
    private void validateFindingsResolved(ReviewTask task) {
        List<Document> documents = documentRepository
                .findByPackageIdAndTenantId(task.packageId(), task.tenantId());

        for (Document document : documents) {
            List<Finding> findings = findingRepository
                    .findByDocumentIdAndTenantId(document.id(), task.tenantId());

            for (Finding finding : findings) {
                if (finding.status() == FindingStatus.DETECTED) {
                    throw new ValidationException(
                            "Cannot complete review: finding " + finding.id() + " has not been reviewed");
                }
                if (finding.status() == FindingStatus.CONFIRMED && !finding.location().hasBoundingBox()) {
                    throw new ValidationException(
                            "Cannot complete review: confirmed finding " + finding.id()
                                    + " is missing a bounding box required for redaction");
                }
            }
        }
    }
}
