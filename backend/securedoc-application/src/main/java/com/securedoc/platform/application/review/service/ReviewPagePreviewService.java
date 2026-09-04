package com.securedoc.platform.application.review.service;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.in.GetReviewPagePreviewQuery;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.FindingRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.ReviewPageRenderPort;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.review.result.ReviewPagePreviewResult;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.finding.FindingLocation;
import com.securedoc.platform.domain.finding.FindingStatus;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.FindingId;
import com.securedoc.platform.domain.identity.TenantId;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides a tenant-scoped, finding-scoped page preview for the V1 Manual Review workbench.
 * The full original file is never exposed by this use case; only the relevant page is rendered.
 */
public final class ReviewPagePreviewService implements GetReviewPagePreviewQuery {

    private static final Logger log = LoggerFactory.getLogger(ReviewPagePreviewService.class);

    private final FindingRepository findingRepository;
    private final DocumentRepository documentRepository;
    private final PackageRepository packageRepository;
    private final ArtifactRepository artifactRepository;
    private final StoragePort storagePort;
    private final ReviewPageRenderPort reviewPageRenderPort;
    private final AuditService auditService;

    public ReviewPagePreviewService(FindingRepository findingRepository,
                                    DocumentRepository documentRepository,
                                    PackageRepository packageRepository,
                                    ArtifactRepository artifactRepository,
                                    StoragePort storagePort,
                                    ReviewPageRenderPort reviewPageRenderPort,
                                    AuditService auditService) {
        this.findingRepository = Objects.requireNonNull(findingRepository);
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.artifactRepository = Objects.requireNonNull(artifactRepository);
        this.storagePort = Objects.requireNonNull(storagePort);
        this.reviewPageRenderPort = Objects.requireNonNull(reviewPageRenderPort);
        this.auditService = Objects.requireNonNull(auditService);
    }

    @Override
    public ReviewPagePreviewResult execute(TenantId tenantId, FindingId findingId,
                                           ActorId actorId, CorrelationId correlationId) {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(findingId);
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(correlationId);

        Finding finding = findingRepository.findByIdAndTenantId(findingId, tenantId)
                .orElseThrow(() -> new NotFoundException("Finding", findingId.toString()));
        if (finding.status() != FindingStatus.DETECTED) {
            log.warn("REVIEW_PREVIEW_REJECTED finding={} reason=ITEM_NOT_PENDING status={}", findingId, finding.status());
            throw new IllegalStateException("Review item is no longer pending; refresh the Manual Review queue");
        }

        Document document = documentRepository.findByIdAndTenantId(finding.documentId(), tenantId)
                .orElseThrow(() -> new NotFoundException("Document", finding.documentId().toString()));
        DocumentPackage pkg = packageRepository.findByIdAndTenantId(document.packageId(), tenantId)
                .orElseThrow(() -> new NotFoundException("DocumentPackage", document.packageId().toString()));
        if (pkg.status() != PackageStatus.PENDING_REVIEW && pkg.status() != PackageStatus.IN_REVIEW) {
            log.warn("REVIEW_PREVIEW_REJECTED finding={} package={} reason=PACKAGE_NOT_IN_REVIEW status={}",
                    findingId, pkg.id(), pkg.status());
            throw new IllegalStateException("Package is no longer awaiting manual review; refresh the Manual Review queue");
        }

        Artifact original = artifactRepository.findByDocumentIdAndTenantId(finding.documentId(), tenantId).stream()
                .filter(artifact -> artifact.artifactType() == ArtifactType.ORIGINAL)
                .filter(Artifact::isAvailable)
                .filter(artifact -> artifact.storageReference() != null)
                .findFirst()
                .orElseThrow(() -> {
                    log.warn("REVIEW_PREVIEW_REJECTED finding={} package={} document={} reason=ORIGINAL_UNAVAILABLE",
                            findingId, pkg.id(), finding.documentId());
                    return new IllegalStateException("Original document is unavailable for review preview");
                });

        int reviewPageNumber = finding.location().pageNumber();
        List<Finding> pageFindings = findingRepository
                .findByDocumentIdAndTenantId(finding.documentId(), tenantId).stream()
                .filter(candidate -> candidate.location().pageNumber() == reviewPageNumber)
                .toList();
        List<Finding> knownSensitiveOnPage = pageFindings.stream()
                .filter(candidate -> candidate.status() == FindingStatus.CONFIRMED
                        || candidate.status() == FindingStatus.REDACTED)
                .toList();
        List<Finding> unmaskableKnownSensitive = knownSensitiveOnPage.stream()
                .filter(candidate -> !candidate.location().hasBoundingBox())
                .toList();
        if (!unmaskableKnownSensitive.isEmpty()) {
            log.error("REVIEW_PREVIEW_REJECTED finding={} package={} document={} page={} "
                            + "reason=KNOWN_SENSITIVE_MASK_UNAVAILABLE knownSensitive={} missingBounds={}",
                    findingId, pkg.id(), finding.documentId(), reviewPageNumber,
                    knownSensitiveOnPage.size(), unmaskableKnownSensitive.size());
            throw new IllegalStateException(
                    "Review preview is unavailable because previously confirmed sensitive content cannot be safely masked; refresh or reprocess the package");
        }
        List<FindingLocation.BoundingBox> previewMasks =
                knownSensitiveOnPage.stream()
                        .map(candidate -> candidate.location().boundingBox())
                        .toList();
        long pendingOnPage = pageFindings.stream()
                .filter(candidate -> candidate.status() == FindingStatus.DETECTED)
                .count();
        log.info("REVIEW_PREVIEW_MASK_PLAN finding={} package={} document={} page={} "
                        + "knownSensitive={} previewMasks={} pendingOnPage={}",
                findingId, pkg.id(), finding.documentId(), reviewPageNumber,
                knownSensitiveOnPage.size(), previewMasks.size(), pendingOnPage);

        try (InputStream content = storagePort.retrieve(original.storageReference())) {
            ReviewPageRenderPort.RenderedPage rendered;
            try {
                rendered = reviewPageRenderPort.render(content, reviewPageNumber, previewMasks);
            } catch (ValidationException ex) {
                log.warn("REVIEW_PREVIEW_REJECTED finding={} package={} document={} page={} reason=PAGE_LOCATION_INVALID",
                        findingId, pkg.id(), finding.documentId(), reviewPageNumber);
                throw new IllegalStateException("The stored finding page is unavailable in the source document; refresh or reprocess the package", ex);
            }
            log.info("REVIEW_PREVIEW_RENDERED finding={} package={} document={} page={} width={} height={} previewMasks={}",
                    findingId, pkg.id(), finding.documentId(), reviewPageNumber,
                    rendered.pixelWidth(), rendered.pixelHeight(), previewMasks.size());
            auditService.recordSuccess(tenantId, actorId,
                    AuditAction.REVIEW_PAGE_PREVIEWED, "Finding", finding.id().toString(),
                    correlationId, "Focused review page preview rendered: page=" + reviewPageNumber
                            + ", previewMasks=" + previewMasks.size());
            return new ReviewPagePreviewResult(
                    rendered.content(), rendered.mimeType(), reviewPageNumber,
                    rendered.pixelWidth(), rendered.pixelHeight());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to close review preview content", e);
        }
    }
}
