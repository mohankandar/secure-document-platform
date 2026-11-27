package com.securedoc.platform.application.review.service;

import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ReviewAlreadyDecidedException;
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
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.finding.DetectorSource;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.vo.Confidence;
import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.finding.FindingLocation;
import com.securedoc.platform.domain.finding.FindingStatus;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.FindingId;
import com.securedoc.platform.application.review.result.ReviewFoundText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

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
    private final ReviewFoundTextReader foundTextReader;

    public FocusedReviewService(PackageRepository packageRepository,
                                DocumentRepository documentRepository,
                                FindingRepository findingRepository,
                                TenantRedactionPolicyRepository tenantRedactionPolicyRepository,
                                PackageProcessingDispatcher packageProcessingDispatcher,
                                AuditService auditService,
                                ReviewFoundTextReader foundTextReader) {
        this.foundTextReader = Objects.requireNonNull(foundTextReader);
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.findingRepository = Objects.requireNonNull(findingRepository);
        this.tenantRedactionPolicyRepository = Objects.requireNonNull(tenantRedactionPolicyRepository);
        this.packageProcessingDispatcher = Objects.requireNonNull(packageProcessingDispatcher);
        this.auditService = Objects.requireNonNull(auditService);
    }

    private com.securedoc.platform.application.intake.CasePriorityService priorities;

    /** Task 0073: the same service, Urgent cases first. Without it every case is Normal. */
    public FocusedReviewService withPriorities(com.securedoc.platform.application.intake.CasePriorityService priorities) {
        this.priorities = Objects.requireNonNull(priorities);
        return this;
    }

    @Override
    public ReviewQueueResult execute(TenantId tenantId, Optional<PackageId> packageId, int limit) {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(packageId);
        int safeLimit = Math.max(1, Math.min(100, limit));
        List<ReviewItemSummary> result = new ArrayList<>();
        Map<FindingId, Finding> findingsById = new HashMap<>();
        Map<DocumentId, Document> documentsById = new HashMap<>();

        // Global review stays tenant-wide. When entered from Intake Details, fetch only that package
        // so the backend enforces the same package scope as the UI instead of client-side filtering.
        List<DocumentPackage> packages = packageId
                .map(id -> List.of(packageRepository.findByIdAndTenantId(id, tenantId)
                        .orElseThrow(() -> new NotFoundException("DocumentPackage", id.toString()))))
                .orElseGet(() -> packageRepository.findByTenantId(tenantId, 0, 250));

        Map<PackageId, com.securedoc.platform.domain.pipeline.PipelinePriority> casePriorities = priorities == null ? Map.of()
                : priorities.find(tenantId, packages.stream().map(DocumentPackage::id).toList());
        Map<PackageId, java.time.Instant> createdAt = new HashMap<>();
        packages.forEach(pkg -> createdAt.put(pkg.id(), pkg.createdAt()));
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
                    findingsById.put(finding.id(), finding);
                    documentsById.put(document.id(), document);
                }
            }
        }

        List<ReviewItemSummary> sorted = result.stream()
                .sorted(Comparator
                        // Task 0073: Urgent, Normal, Low; the oldest case first within each.
                        .comparing((ReviewItemSummary item) -> casePriorities.getOrDefault(item.packageId(),
                                com.securedoc.platform.domain.pipeline.PipelinePriority.NORMAL).rank())
                        .thenComparing(item -> createdAt.get(item.packageId()))
                        .thenComparing(item -> item.packageId().toString())
                        .thenComparing(item -> item.documentId().toString())
                        .thenComparingInt(ReviewItemSummary::pageNumber)
                        .thenComparing(item -> item.findingId().toString()))
                .toList();
        List<ReviewItemSummary> page = sorted.stream().limit(safeLimit).toList();
        double threshold = tenantRedactionPolicyRepository.findByTenantId(tenantId)
                .orElseGet(() -> TenantRedactionPolicy.defaults(tenantId))
                .autoRedactThreshold();
        return new ReviewQueueResult(withFoundText(page, findingsById, documentsById), sorted.size(), threshold);
    }

    /**
     * Adds the found text to the returned items only, reading each document's text once.
     * Items whose text cannot be read safely keep {@code foundText == null}.
     */
    private List<ReviewItemSummary> withFoundText(List<ReviewItemSummary> page,
                                                  Map<FindingId, Finding> findingsById,
                                                  Map<DocumentId, Document> documentsById) {
        Map<DocumentId, List<Finding>> byDocument = new LinkedHashMap<>();
        for (ReviewItemSummary item : page) {
            byDocument.computeIfAbsent(item.documentId(), id -> new ArrayList<>())
                    .add(findingsById.get(item.findingId()));
        }
        Map<FindingId, ReviewFoundText> texts = new HashMap<>();
        byDocument.forEach((documentId, findings) ->
                texts.putAll(foundTextReader.read(documentsById.get(documentId), findings)));
        return page.stream()
                .map(item -> item.withFoundText(texts.get(item.findingId())))
                .toList();
    }

    /**
     * Task 0023 (D5, #160): two reviewers deciding the same item. The repository saves the decision only while the
     * stored item is still pending (and refuses a save that meets another in the database, instead of a 500), so
     * the second reviewer gets the same code as above.
     */
    private void saveDecision(Finding decided) {
        if (!findingRepository.saveDecisionIfPending(decided)) throw new ReviewAlreadyDecidedException();
    }

    @Override
    public ResolveReviewItemResult execute(ResolveReviewItemCommand command) {
        Objects.requireNonNull(command);

        Finding finding = findingRepository.findByIdAndTenantId(command.findingId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("Finding", command.findingId().toString()));
        if (finding.status() != FindingStatus.DETECTED) {
            // Task 0023 (D5, #160): one code for "already decided by someone else" (409 REVIEW_ALREADY_DECIDED).
            throw new ReviewAlreadyDecidedException();
        }

        Document document = documentRepository.findByIdAndTenantId(finding.documentId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("Document", finding.documentId().toString()));
        DocumentPackage pkg = packageRepository.findByIdAndTenantId(document.packageId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("DocumentPackage", document.packageId().toString()));
        if (pkg.status() != PackageStatus.PENDING_REVIEW && pkg.status() != PackageStatus.IN_REVIEW) {
            throw new ValidationException(LvErrorCode.REV_002, "Package is not awaiting manual review");
        }

        if (finding.entityType() == EntityType.UNREAD_PAGE) {
            // Task 0074: only the page check decides it, with "I have reviewed this page".
            throw new ValidationException(LvErrorCode.REV_004, "Confirm that you have reviewed this page");
        }

        // Task 0105: only a box that differs from the one there is a person's placing; the same box sent with a plain
        // confirmation changes nothing and is not recorded as moved.
        boolean moved = command.hasBoundingBoxAdjustment() && finding.placeByPerson(FindingLocation.BoundingBox.of(
                command.left(), command.top(), command.width(), command.height()), command.actorId(), java.time.Instant.now());

        String safeReason = ReviewTextSanitizer.sanitizeReason(command.reason());
        if (command.resolution() == ReviewResolution.CONFIRM) {
            if (!finding.location().hasBoundingBox()) {
                throw new ValidationException(LvErrorCode.REV_006, "Finding location must be adjusted before confirmation");
            }
            finding.confirm(command.actorId(), safeReason);
        } else {
            finding.dismiss(command.actorId(), safeReason);
        }
        // Task 0023 (D5): the decision is saved before it is audited, so a refused (already decided) decision
        // leaves no audit row that claims it was made.
        saveDecision(finding);
        if (moved) {
            auditService.recordSuccess(command.tenantId(), command.actorId(),
                    AuditAction.FINDING_BOUNDING_BOX_UPDATED, "Finding", finding.id().toString(), pkg.id(),
                    command.correlationId(), "Finding location adjusted during focused review");
        }
        if (command.resolution() == ReviewResolution.CONFIRM) {
            auditService.recordSuccess(command.tenantId(), command.actorId(),
                    AuditAction.FINDING_CONFIRMED, "Finding", finding.id().toString(), pkg.id(),
                    command.correlationId(), "Focused review confirmed finding: type=" + finding.entityType());
        } else {
            auditService.recordSuccess(command.tenantId(), command.actorId(),
                    AuditAction.FINDING_DISMISSED, "Finding", finding.id().toString(), pkg.id(),
                    command.correlationId(), "Focused review dismissed finding: type=" + finding.entityType());
        }

        return new ResolveReviewItemResult(finding.id(), pkg.id(), finding.status(),
                resumeWhenDecided(pkg, command.tenantId(), command.actorId(), command.correlationId()));
    }

    /** The boxes one page check may draw. */
    private static final int MAX_BOXES = 200;

    /**
     * Task 0074: a person reviewed an unreadable page by hand and drew any number of boxes on it (none when it holds
     * nothing to redact). Each box becomes a confirmed finding, so it is redacted as any other; then the "Page to check"
     * is decided. Run it in one transaction: the boxes are never saved without the decision, nor the decision without
     * its boxes. The audit record has the page number and the number of boxes, never content.
     */
    public ResolveReviewItemResult checkPage(FindingId findingId, TenantId tenantId, ActorId actorId,
                                             CorrelationId correlationId, boolean reviewed,
                                             List<FindingLocation.BoundingBox> boxes) {
        if (!reviewed) throw new ValidationException(LvErrorCode.REV_004, "Confirm that you have reviewed this page");
        if (boxes.size() > MAX_BOXES) {
            throw new ValidationException(LvErrorCode.REV_006, "A page can have up to " + MAX_BOXES + " boxes");
        }
        Finding page = findingRepository.findByIdAndTenantId(findingId, tenantId)
                .orElseThrow(() -> new NotFoundException("Finding", findingId.toString()));
        if (page.status() != FindingStatus.DETECTED) throw new ReviewAlreadyDecidedException();
        if (page.entityType() != EntityType.UNREAD_PAGE) {
            throw new ValidationException(LvErrorCode.REV_006, "Only a page to check can be checked by hand");
        }
        Document document = documentRepository.findByIdAndTenantId(page.documentId(), tenantId)
                .orElseThrow(() -> new NotFoundException("Document", page.documentId().toString()));
        DocumentPackage pkg = packageRepository.findByIdAndTenantId(document.packageId(), tenantId)
                .orElseThrow(() -> new NotFoundException("DocumentPackage", document.packageId().toString()));
        if (pkg.status() != PackageStatus.PENDING_REVIEW && pkg.status() != PackageStatus.IN_REVIEW) {
            throw new ValidationException(LvErrorCode.REV_002, "Package is not awaiting manual review");
        }
        List<Finding> drawn = new ArrayList<>();
        for (FindingLocation.BoundingBox box : boxes) {
            Finding finding = Finding.create(tenantId, document.id(), EntityType.UNREAD_PAGE,
                    FindingLocation.withBounds(page.location().pageNumber(), 0, 0, box), Confidence.full(),
                    DetectorSource.of("review-page-check", "1.0.0"),
                    // Task 0105: a page check, drawn by a person; never without a reader.
                    com.securedoc.platform.domain.finding.FindingOrigin.of(
                            com.securedoc.platform.domain.finding.DetectionPath.PAGE_CHECK, List.of()));
            finding.applyPlacement(com.securedoc.platform.domain.finding.FindingPlacement.drawnBy(actorId, java.time.Instant.now()));
            finding.confirm(actorId, "Drawn by a person on an unreadable page");
            drawn.add(finding);
        }
        if (!drawn.isEmpty()) findingRepository.saveAll(drawn);
        page.dismiss(actorId, "Page reviewed by hand");
        saveDecision(page);
        auditService.recordSuccess(tenantId, actorId, AuditAction.REVIEW_PAGE_CHECKED, "Finding", page.id().toString(),
                pkg.id(), correlationId, "Unreadable page checked by hand: document=" + document.id()
                        + ", page=" + page.location().pageNumber() + ", boxes=" + drawn.size());
        return new ResolveReviewItemResult(page.id(), pkg.id(), page.status(),
                resumeWhenDecided(pkg, tenantId, actorId, correlationId));
    }

    /** After the last open finding the case goes on; true when it did. */
    private boolean resumeWhenDecided(DocumentPackage pkg, TenantId tenantId, ActorId actorId,
                                      CorrelationId correlationId) {
        boolean processingResumed = false;
        if (!packageHasPendingFindings(pkg.id(), tenantId)) {
            // The human decision is complete at this point. Move the package out of review immediately
            // and dispatch the potentially slow redaction/verification/export continuation separately so
            // the review HTTP response is not held open while background document work runs.
            pkg.resumeProcessingAfterReview();
            if (!packageRepository.changeStatus(pkg, java.util.Set.of(PackageStatus.PENDING_REVIEW, PackageStatus.IN_REVIEW))) {
                // Task 0071: rejected meanwhile. The rejection stands.
                throw new ValidationException(LvErrorCode.REV_002, "Package is not awaiting manual review");
            }
            auditService.recordSuccess(tenantId, actorId,
                    AuditAction.PACKAGE_PROCESSING_STARTED, "DocumentPackage", pkg.id().toString(),
                    correlationId, "Package processing resumed after final manual-review decision");

            packageProcessingDispatcher.dispatch(TriggerPackageProcessingCommand.of(
                    pkg.id(), tenantId, actorId, correlationId));
            processingResumed = true;
        }

        return processingResumed;
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
                finding.location().hasBoundingBox(), boxSummary, reason, null,
                finding.origin().paths().stream().map(Enum::name).toList(),
                checkKinds(finding));
    }

    /** The kinds of a page a check of the finished file sent; the final check's start with FINAL (task 0099). */
    private static java.util.List<String> checkKinds(Finding finding) {
        java.util.List<String> second = com.securedoc.platform.application.secondcheck.SecondCheck.kindsOf(finding);
        if (second != null) return second;
        java.util.List<String> fin = com.securedoc.platform.application.secondcheck.FinalPatternCheck.kindsOf(finding);
        if (fin == null) return null;
        java.util.List<String> kinds = new java.util.ArrayList<>(fin.size() + 1);
        kinds.add("FINAL");
        kinds.addAll(fin);
        return kinds;
    }

    private boolean packageHasPendingFindings(PackageId packageId, TenantId tenantId) {
        return documentRepository.findByPackageIdAndTenantId(packageId, tenantId).stream()
                .flatMap(document -> findingRepository.findByDocumentIdAndTenantId(document.id(), tenantId).stream())
                .anyMatch(finding -> finding.status() == FindingStatus.DETECTED);
    }
}
