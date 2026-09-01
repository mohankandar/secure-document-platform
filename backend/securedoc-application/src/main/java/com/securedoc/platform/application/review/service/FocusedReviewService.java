package com.securedoc.platform.application.review.service;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.in.ListReviewItemsQuery;
import com.securedoc.platform.application.port.in.ResolveReviewItemUseCase;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.FindingRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.PackageProcessingDispatcher;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.application.processing.command.TriggerPackageProcessingCommand;
import com.securedoc.platform.application.review.command.ResolveReviewItemCommand;
import com.securedoc.platform.application.review.model.ReviewReason;
import com.securedoc.platform.application.review.model.ReviewResolution;
import com.securedoc.platform.application.review.result.ResolveReviewItemResult;
import com.securedoc.platform.application.review.result.ReviewBoundingBoxSummary;
import com.securedoc.platform.application.review.result.ReviewItemSummary;
import com.securedoc.platform.application.review.result.ReviewQueueResult;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.business.ReviewMode;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.finding.FindingLocation;
import com.securedoc.platform.domain.finding.FindingStatus;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * V1 finding-focused review service. It deliberately avoids assignment/create/start task mechanics:
 * role authorization is enforced at the API edge, and resolving the last uncertain finding
 * automatically resumes redaction/delivery.
 */
public final class FocusedReviewService implements ListReviewItemsQuery, ResolveReviewItemUseCase {

    private final PackageRepository packageRepository;
    private final DocumentRepository documentRepository;
    private final FindingRepository findingRepository;
    private final TenantRedactionPolicyRepository tenantRedactionPolicyRepository;
    private final PackageProcessingDispatcher packageProcessingDispatcher;
    private final AuditService auditService;

    public FocusedReviewService(PackageRepository packageRepository,
                                DocumentRepository documentRepository,
                                FindingRepository findingRepository,
                                TenantRedactionPolicyRepository tenantRedactionPolicyRepository,
                                PackageProcessingDispatcher packageProcessingDispatcher,
                                AuditService auditService) {
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.findingRepository = Objects.requireNonNull(findingRepository);
        this.tenantRedactionPolicyRepository = Objects.requireNonNull(tenantRedactionPolicyRepository);
        this.packageProcessingDispatcher = Objects.requireNonNull(packageProcessingDispatcher);
        this.auditService = Objects.requireNonNull(auditService);
    }

    @Override
    public ReviewQueueResult execute(TenantId tenantId, Optional<PackageId> packageId, int limit) {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(packageId);
        int safeLimit = Math.max(1, Math.min(100, limit));
        List<ReviewItemSummary> result = new ArrayList<>();

        // Global review stays tenant-wide. When entered from Intake Details, fetch only that package
        // so the backend enforces the same package scope as the UI instead of client-side filtering.
        List<DocumentPackage> packages = packageId
                .map(id -> List.of(packageRepository.findByIdAndTenantId(id, tenantId)
                        .orElseThrow(() -> new NotFoundException("DocumentPackage", id.toString()))))
                .orElseGet(() -> packageRepository.findByTenantId(tenantId, 0, 250));

        for (DocumentPackage pkg : packages) {
            if (pkg.status() != PackageStatus.PENDING_REVIEW && pkg.status() != PackageStatus.IN_REVIEW) {
                continue;
            }
            for (Document document : documentRepository.findByPackageIdAndTenantId(pkg.id(), tenantId)) {
                for (Finding finding : findingRepository.findByDocumentIdAndTenantId(document.id(), tenantId)) {
                    // Actionable queue means a finding that is still pending on a package that is still in review.
                    // Historical confirmed/dismissed/redacted findings never contribute to the active count.
                    if (finding.status() != FindingStatus.DETECTED || finding.location().pageNumber() < 1) {
                        continue;
                    }
                    result.add(toSummary(pkg.id(), document, finding));
                }
            }
        }

        List<ReviewItemSummary> sorted = result.stream()
                .sorted(Comparator
                        .comparing((ReviewItemSummary item) -> item.packageId().toString())
                        .thenComparing(item -> item.documentId().toString())
                        .thenComparingInt(ReviewItemSummary::pageNumber)
                        .thenComparing(item -> item.findingId().toString()))
                .toList();
        return new ReviewQueueResult(sorted.stream().limit(safeLimit).toList(), sorted.size());
    }

    @Override
    public ResolveReviewItemResult execute(ResolveReviewItemCommand command) {
        Objects.requireNonNull(command);

        Finding finding = findingRepository.findByIdAndTenantId(command.findingId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("Finding", command.findingId().toString()));
        if (finding.status() != FindingStatus.DETECTED) {
            throw new ValidationException("Review item is no longer pending");
        }

        Document document = documentRepository.findByIdAndTenantId(finding.documentId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("Document", finding.documentId().toString()));
        DocumentPackage pkg = packageRepository.findByIdAndTenantId(document.packageId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("DocumentPackage", document.packageId().toString()));
        if (pkg.status() != PackageStatus.PENDING_REVIEW && pkg.status() != PackageStatus.IN_REVIEW) {
            throw new ValidationException("Package is not awaiting manual review");
        }

        if (command.hasBoundingBoxAdjustment()) {
            FindingLocation current = finding.location();
            FindingLocation.BoundingBox box = FindingLocation.BoundingBox.of(
                    command.left(), command.top(), command.width(), command.height());
            finding.updateLocation(FindingLocation.withBounds(
                    current.pageNumber(), current.startOffset(), current.endOffset(), box));
            auditService.recordSuccess(command.tenantId(), command.actorId(),
                    AuditAction.FINDING_BOUNDING_BOX_UPDATED, "Finding", finding.id().toString(),
                    command.correlationId(), "Finding location adjusted during focused review");
        }

        String safeReason = ReviewTextSanitizer.sanitizeReason(command.reason());
        if (command.resolution() == ReviewResolution.CONFIRM) {
            if (!finding.location().hasBoundingBox()) {
                throw new ValidationException("Finding location must be adjusted before confirmation");
            }
            finding.confirm(command.actorId(), safeReason);
            auditService.recordSuccess(command.tenantId(), command.actorId(),
                    AuditAction.FINDING_CONFIRMED, "Finding", finding.id().toString(),
                    command.correlationId(), "Focused review confirmed finding: type=" + finding.entityType());
        } else {
            finding.dismiss(command.actorId(), safeReason);
            auditService.recordSuccess(command.tenantId(), command.actorId(),
                    AuditAction.FINDING_DISMISSED, "Finding", finding.id().toString(),
                    command.correlationId(), "Focused review dismissed finding: type=" + finding.entityType());
        }
        findingRepository.save(finding);

        boolean processingResumed = false;
        if (!packageHasPendingFindings(pkg.id(), command.tenantId())) {
            // The human decision is complete at this point. Move the package out of review immediately
            // and dispatch the potentially slow redaction/verification/export continuation separately so
            // the review HTTP response is not held open while background document work runs.
            pkg.resumeProcessingAfterReview();
            packageRepository.save(pkg);
            auditService.recordSuccess(command.tenantId(), command.actorId(),
                    AuditAction.PACKAGE_PROCESSING_STARTED, "DocumentPackage", pkg.id().toString(),
                    command.correlationId(), "Package processing resumed after final manual-review decision");

            packageProcessingDispatcher.dispatch(TriggerPackageProcessingCommand.of(
                    pkg.id(), command.tenantId(), command.actorId(), command.correlationId()));
            processingResumed = true;
        }

        return new ResolveReviewItemResult(finding.id(), pkg.id(), finding.status(), processingResumed);
    }

    private ReviewItemSummary toSummary(PackageId packageId, Document document, Finding finding) {
        ReviewReason reason;
        if (!finding.location().hasBoundingBox()) {
            reason = ReviewReason.LOCATION_UNCERTAIN;
        } else {
            TenantRedactionPolicy policy = tenantRedactionPolicyRepository.findByTenantId(document.tenantId())
                    .orElseGet(() -> TenantRedactionPolicy.defaults(document.tenantId()));
            if (policy.reviewMode() == ReviewMode.REVIEW_ALL) {
                reason = ReviewReason.POLICY_REVIEW;
            } else if (finding.confidence().value() < policy.autoRedactThreshold()) {
                reason = ReviewReason.LOW_CONFIDENCE;
            } else {
                reason = ReviewReason.POLICY_REVIEW;
            }
        }
        FindingLocation.BoundingBox box = finding.location().boundingBox();
        ReviewBoundingBoxSummary boxSummary = box == null ? null : new ReviewBoundingBoxSummary(
                box.left(), box.top(), box.width(), box.height());
        return new ReviewItemSummary(
                finding.id(), packageId, document.id(), document.fileName().value(), finding.entityType(),
                finding.confidence().value(), finding.location().pageNumber(),
                finding.location().hasBoundingBox(), boxSummary, reason);
    }

    private boolean packageHasPendingFindings(PackageId packageId, TenantId tenantId) {
        return documentRepository.findByPackageIdAndTenantId(packageId, tenantId).stream()
                .flatMap(document -> findingRepository.findByDocumentIdAndTenantId(document.id(), tenantId).stream())
                .anyMatch(finding -> finding.status() == FindingStatus.DETECTED);
    }
}
