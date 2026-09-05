package com.securedoc.platform.application.manual.service;

import com.securedoc.platform.application.audit.query.AuditRecordFilter;
import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.manual.result.ManualFallbackPagePreviewResult;
import com.securedoc.platform.application.port.in.GetManualFallbackPagePreviewQuery;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.AuditQueryPort;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.FindingRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.ReviewPageRenderPort;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.finding.FindingLocation;
import com.securedoc.platform.domain.finding.FindingStatus;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;

/** Renders one original PDF page for the full-document business manual fallback workbench. */
public final class ManualFallbackPagePreviewService implements GetManualFallbackPagePreviewQuery {
    private final PackageRepository packageRepository;
    private final DocumentRepository documentRepository;
    private final FindingRepository findingRepository;
    private final ArtifactRepository artifactRepository;
    private final AuditQueryPort auditQueryPort;
    private final StoragePort storagePort;
    private final ReviewPageRenderPort reviewPageRenderPort;
    private final AuditService auditService;

    public ManualFallbackPagePreviewService(PackageRepository packageRepository,
                                            DocumentRepository documentRepository,
                                            FindingRepository findingRepository,
                                            ArtifactRepository artifactRepository,
                                            AuditQueryPort auditQueryPort,
                                            StoragePort storagePort,
                                            ReviewPageRenderPort reviewPageRenderPort,
                                            AuditService auditService) {
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.findingRepository = Objects.requireNonNull(findingRepository);
        this.artifactRepository = Objects.requireNonNull(artifactRepository);
        this.auditQueryPort = Objects.requireNonNull(auditQueryPort);
        this.storagePort = Objects.requireNonNull(storagePort);
        this.reviewPageRenderPort = Objects.requireNonNull(reviewPageRenderPort);
        this.auditService = Objects.requireNonNull(auditService);
    }

    @Override
    public ManualFallbackPagePreviewResult execute(PackageId packageId, DocumentId documentId, int pageNumber,
                                                    TenantId tenantId, ActorId actorId,
                                                    CorrelationId correlationId) {
        var pkg = packageRepository.findByIdAndTenantId(packageId, tenantId)
                .orElseThrow(() -> new NotFoundException("DocumentPackage", packageId.toString()));
        if (pkg.status() != PackageStatus.IN_REVIEW || !manualFallbackStarted(packageId, tenantId)) {
            throw new ValidationException("Package is not in business manual processing");
        }
        var document = documentRepository.findByIdAndTenantId(documentId, tenantId)
                .orElseThrow(() -> new NotFoundException("Document", documentId.toString()));
        if (!document.packageId().equals(packageId) || document.status() != DocumentStatus.REDACTING) {
            throw new ValidationException("Document is not part of the active manual processing session");
        }
        if (pageNumber < 1 || pageNumber > document.pageCount()) {
            throw new ValidationException("Page number is outside the source document");
        }

        Artifact original = artifactRepository.findByDocumentIdAndTenantId(documentId, tenantId).stream()
                .filter(a -> a.artifactType() == ArtifactType.ORIGINAL && a.isAvailable() && a.storageReference() != null)
                .findFirst()
                .orElseThrow(() -> new ValidationException("Original document is unavailable for manual processing"));

        var pageFindings = findingRepository.findByDocumentIdAndTenantId(documentId, tenantId).stream()
                .filter(f -> f.location().pageNumber() == pageNumber)
                .filter(f -> f.status() == FindingStatus.CONFIRMED || f.status() == FindingStatus.REDACTED)
                .toList();
        if (pageFindings.stream().anyMatch(f -> !f.location().hasBoundingBox())) {
            throw new ValidationException("A confirmed sensitive region cannot be safely masked in the preview");
        }
        List<FindingLocation.BoundingBox> masks = pageFindings.stream()
                .map(f -> f.location().boundingBox()).toList();

        try (InputStream content = storagePort.retrieve(original.storageReference())) {
            ReviewPageRenderPort.RenderedPage rendered = reviewPageRenderPort.render(content, pageNumber, masks);
            auditService.recordSuccess(tenantId, actorId, AuditAction.REVIEW_PAGE_PREVIEWED,
                    "Document", documentId.toString(), correlationId,
                    "Business manual fallback page preview rendered: page=" + pageNumber
                            + ", previewMasks=" + masks.size());
            return new ManualFallbackPagePreviewResult(rendered.content(), rendered.mimeType(), pageNumber,
                    rendered.pixelWidth(), rendered.pixelHeight());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to close manual fallback preview content", e);
        }
    }

    private boolean manualFallbackStarted(PackageId packageId, TenantId tenantId) {
        return !auditQueryPort.findByFilter(new AuditRecordFilter(
                tenantId, AuditAction.BUSINESS_MANUAL_FALLBACK_STARTED.name(), null,
                "DocumentPackage", packageId.toString(), null, null, null, null, 10)).isEmpty();
    }
}
