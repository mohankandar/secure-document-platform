package com.securedoc.platform.application.manual.service;

import com.securedoc.platform.application.audit.query.AuditRecordFilter;
import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.manual.command.AddManualRedactionRegionCommand;
import com.securedoc.platform.application.manual.command.ManualFallbackCommand;
import com.securedoc.platform.application.manual.command.RemoveManualRedactionRegionCommand;
import com.securedoc.platform.application.manual.command.SubmitManualFallbackCommand;
import com.securedoc.platform.application.manual.result.ManualFallbackDetail;
import com.securedoc.platform.application.manual.result.ManualFallbackDocumentSummary;
import com.securedoc.platform.application.manual.result.ManualRedactionRegionSummary;
import com.securedoc.platform.application.port.in.AddManualRedactionRegionUseCase;
import com.securedoc.platform.application.port.in.GetManualFallbackQuery;
import com.securedoc.platform.application.port.in.GetTechnicalExceptionQuery;
import com.securedoc.platform.application.port.in.RemoveManualRedactionRegionUseCase;
import com.securedoc.platform.application.port.in.StartManualFallbackUseCase;
import com.securedoc.platform.application.port.in.SubmitManualFallbackUseCase;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.AuditQueryPort;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.FindingRepository;
import com.securedoc.platform.application.port.out.PackageProcessingDispatcher;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.application.processing.command.TriggerPackageProcessingCommand;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.artifact.ArtifactStatus;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.finding.DetectorSource;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.finding.FindingLocation;
import com.securedoc.platform.domain.finding.FindingStatus;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.vo.Confidence;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Business-continuity fallback for AI-analysis failures that remain technically unresolved.
 * The business user reviews the failed document in full, adds explicit redaction regions, and
 * resumes the normal deterministic redaction/verification/delivery pipeline.
 */
public final class ManualFallbackService implements StartManualFallbackUseCase, GetManualFallbackQuery,
        AddManualRedactionRegionUseCase, RemoveManualRedactionRegionUseCase, SubmitManualFallbackUseCase {

    static final String MANUAL_DETECTOR = "MANUAL_FALLBACK";
    static final String MANUAL_DETECTOR_VERSION = "1.0.0";

    private final PackageRepository packageRepository;
    private final DocumentRepository documentRepository;
    private final FindingRepository findingRepository;
    private final ArtifactRepository artifactRepository;
    private final TenantRedactionPolicyRepository tenantRedactionPolicyRepository;
    private final AuditQueryPort auditQueryPort;
    private final GetTechnicalExceptionQuery technicalExceptionQuery;
    private final PackageProcessingDispatcher packageProcessingDispatcher;
    private final AuditService auditService;

    public ManualFallbackService(PackageRepository packageRepository,
                                 DocumentRepository documentRepository,
                                 FindingRepository findingRepository,
                                 ArtifactRepository artifactRepository,
                                 TenantRedactionPolicyRepository tenantRedactionPolicyRepository,
                                 AuditQueryPort auditQueryPort,
                                 GetTechnicalExceptionQuery technicalExceptionQuery,
                                 PackageProcessingDispatcher packageProcessingDispatcher,
                                 AuditService auditService) {
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.findingRepository = Objects.requireNonNull(findingRepository);
        this.artifactRepository = Objects.requireNonNull(artifactRepository);
        this.tenantRedactionPolicyRepository = Objects.requireNonNull(tenantRedactionPolicyRepository);
        this.auditQueryPort = Objects.requireNonNull(auditQueryPort);
        this.technicalExceptionQuery = Objects.requireNonNull(technicalExceptionQuery);
        this.packageProcessingDispatcher = Objects.requireNonNull(packageProcessingDispatcher);
        this.auditService = Objects.requireNonNull(auditService);
    }

    @Override
    public ManualFallbackDetail execute(ManualFallbackCommand command) {
        Objects.requireNonNull(command);
        DocumentPackage pkg = loadPackage(command.packageId(), command.tenantId());
        if (pkg.status() != PackageStatus.FAILED) {
            throw new ValidationException("Manual processing can start only after automatic processing has failed");
        }

        var technical = technicalExceptionQuery.execute(command.packageId(), command.tenantId());
        if (!"AI_ANALYSIS".equals(technical.failedStage())) {
            throw new ValidationException(
                    "Manual processing is available only when sensitive-data analysis failed and the PDF remains safe to render and redact");
        }

        List<Document> documents = documentRepository.findByPackageIdAndTenantId(command.packageId(), command.tenantId());
        List<Document> failed = documents.stream().filter(d -> d.status() == DocumentStatus.FAILED).toList();
        if (failed.isEmpty()) {
            throw new ValidationException("No failed document is available for manual processing");
        }
        if (documents.stream().anyMatch(d -> d.status() != DocumentStatus.FAILED && d.status() != DocumentStatus.PROCESSED)) {
            throw new ValidationException("Package contains document work that is not safe to continue manually");
        }

        for (Document document : failed) {
            if (document.pageCount() < 1) {
                throw new ValidationException("Manual processing requires known source pages for every failed document");
            }
            if (!hasAvailableOriginal(document.id(), command.tenantId())) {
                throw new ValidationException("The retained original is unavailable for manual processing");
            }
            boolean unresolvedFindings = findingRepository.findByDocumentIdAndTenantId(document.id(), command.tenantId())
                    .stream().anyMatch(f -> f.status() == FindingStatus.DETECTED);
            if (unresolvedFindings) {
                throw new ValidationException("Existing findings still require normal Manual Review before manual fallback can start");
            }
        }

        for (Document document : failed) {
            document.prepareForManualFallback();
            documentRepository.save(document);
        }
        pkg.prepareForManualFallback();
        packageRepository.save(pkg);

        auditService.recordSuccess(command.tenantId(), command.actorId(),
                AuditAction.BUSINESS_MANUAL_FALLBACK_STARTED, "DocumentPackage", pkg.id().toString(),
                command.correlationId(),
                "Business manual fallback started: failedStage=AI_ANALYSIS, documents=" + failed.size()
                        + ", reviewScope=FULL_DOCUMENT");
        return buildDetail(pkg, command.tenantId());
    }

    @Override
    public ManualFallbackDetail execute(PackageId packageId, TenantId tenantId) {
        DocumentPackage pkg = loadPackage(packageId, tenantId);
        requireActiveManualFallback(pkg, tenantId);
        return buildDetail(pkg, tenantId);
    }

    @Override
    public ManualRedactionRegionSummary execute(AddManualRedactionRegionCommand command) {
        Objects.requireNonNull(command);
        DocumentPackage pkg = loadPackage(command.packageId(), command.tenantId());
        requireActiveManualFallback(pkg, command.tenantId());
        Document document = documentRepository.findByIdAndTenantId(command.documentId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("Document", command.documentId().toString()));
        if (!document.packageId().equals(command.packageId()) || document.status() != DocumentStatus.REDACTING) {
            throw new ValidationException("Document is not part of the active manual processing session");
        }
        if (command.pageNumber() < 1 || command.pageNumber() > document.pageCount()) {
            throw new ValidationException("Page number is outside the source document");
        }

        TenantRedactionPolicy policy = tenantRedactionPolicyRepository.findByTenantId(command.tenantId())
                .orElseGet(() -> TenantRedactionPolicy.defaults(command.tenantId()));
        if (!policy.isEnabled(command.entityType())) {
            throw new ValidationException("Selected sensitive-data type is not enabled by tenant policy");
        }

        FindingLocation.BoundingBox box;
        try {
            box = FindingLocation.BoundingBox.of(command.left(), command.top(), command.width(), command.height());
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("Manual redaction region is outside the page bounds");
        }
        Finding finding = Finding.create(command.tenantId(), command.documentId(), command.entityType(),
                FindingLocation.withBounds(command.pageNumber(), 0, 0, box), Confidence.full(),
                DetectorSource.of(MANUAL_DETECTOR, MANUAL_DETECTOR_VERSION), null);
        finding.confirm(command.actorId(), "Business user selected manual redaction region");
        findingRepository.save(finding);

        auditService.recordSuccess(command.tenantId(), command.actorId(),
                AuditAction.BUSINESS_MANUAL_FALLBACK_REGION_ADDED, "Finding", finding.id().toString(),
                command.correlationId(), "Manual fallback region added: page=" + command.pageNumber()
                        + ", type=" + command.entityType());
        return toRegion(finding);
    }

    @Override
    public void execute(RemoveManualRedactionRegionCommand command) {
        Objects.requireNonNull(command);
        DocumentPackage pkg = loadPackage(command.packageId(), command.tenantId());
        requireActiveManualFallback(pkg, command.tenantId());
        Finding finding = findingRepository.findByIdAndTenantId(command.findingId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("Finding", command.findingId().toString()));
        Document document = documentRepository.findByIdAndTenantId(finding.documentId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("Document", finding.documentId().toString()));
        if (!document.packageId().equals(command.packageId())
                || !MANUAL_DETECTOR.equals(finding.detectorSource().detectorName())
                || finding.status() != FindingStatus.CONFIRMED) {
            throw new ValidationException("Manual redaction region is not active for this package");
        }
        finding.override(command.actorId(), "Business user removed manual redaction region");
        findingRepository.save(finding);
        auditService.recordSuccess(command.tenantId(), command.actorId(),
                AuditAction.BUSINESS_MANUAL_FALLBACK_REGION_REMOVED, "Finding", finding.id().toString(),
                command.correlationId(), "Manual fallback region removed: page=" + finding.location().pageNumber()
                        + ", type=" + finding.entityType());
    }

    @Override
    public void execute(SubmitManualFallbackCommand command) {
        Objects.requireNonNull(command);
        if (!command.fullDocumentReviewed()) {
            throw new ValidationException("Confirm that every page was reviewed before submitting manual processing");
        }
        DocumentPackage pkg = loadPackage(command.packageId(), command.tenantId());
        requireActiveManualFallback(pkg, command.tenantId());
        List<Document> manualDocuments = manualDocuments(command.packageId(), command.tenantId());
        if (manualDocuments.isEmpty()) {
            throw new ValidationException("No document is active for manual processing");
        }

        int activeRegions = 0;
        for (Document document : manualDocuments) {
            if (!hasAvailableOriginal(document.id(), command.tenantId())) {
                throw new ValidationException("The retained original is unavailable for manual processing");
            }
            List<Finding> findings = findingRepository.findByDocumentIdAndTenantId(document.id(), command.tenantId());
            if (findings.stream().anyMatch(f -> f.status() == FindingStatus.DETECTED)) {
                throw new ValidationException("Document still contains unresolved findings");
            }
            if (findings.stream().anyMatch(f -> f.status() == FindingStatus.CONFIRMED && !f.location().hasBoundingBox())) {
                throw new ValidationException("A confirmed redaction does not have a safe page location");
            }
            activeRegions += (int) findings.stream()
                    .filter(f -> f.status() == FindingStatus.CONFIRMED
                            && MANUAL_DETECTOR.equals(f.detectorSource().detectorName()))
                    .count();
        }

        pkg.resumeProcessingAfterReview();
        packageRepository.save(pkg);
        auditService.recordSuccess(command.tenantId(), command.actorId(),
                AuditAction.BUSINESS_MANUAL_FALLBACK_SUBMITTED, "DocumentPackage", pkg.id().toString(),
                command.correlationId(), "Business manual fallback submitted: documents=" + manualDocuments.size()
                        + ", manualRegions=" + activeRegions + ", fullDocumentReviewed=true");
        packageProcessingDispatcher.dispatch(TriggerPackageProcessingCommand.of(
                command.packageId(), command.tenantId(), command.actorId(), command.correlationId()));
    }

    private ManualFallbackDetail buildDetail(DocumentPackage pkg, TenantId tenantId) {
        TenantRedactionPolicy policy = tenantRedactionPolicyRepository.findByTenantId(tenantId)
                .orElseGet(() -> TenantRedactionPolicy.defaults(tenantId));
        List<ManualFallbackDocumentSummary> documents = manualDocuments(pkg.id(), tenantId).stream()
                .sorted(Comparator.comparing(d -> d.fileName().value()))
                .map(d -> new ManualFallbackDocumentSummary(d.id(), d.fileName().value(), d.pageCount()))
                .toList();
        List<ManualRedactionRegionSummary> regions = documents.stream()
                .flatMap(summary -> findingRepository.findByDocumentIdAndTenantId(summary.documentId(), tenantId).stream())
                .filter(f -> f.status() == FindingStatus.CONFIRMED
                        && MANUAL_DETECTOR.equals(f.detectorSource().detectorName()))
                .sorted(Comparator.comparing((Finding f) -> f.documentId().toString())
                        .thenComparingInt(f -> f.location().pageNumber())
                        .thenComparing(f -> f.id().toString()))
                .map(this::toRegion)
                .toList();
        return new ManualFallbackDetail(pkg.id(), pkg.status().name(), documents, regions,
                Set.copyOf(policy.enabledEntityTypes()));
    }

    private ManualRedactionRegionSummary toRegion(Finding finding) {
        var box = finding.location().boundingBox();
        return new ManualRedactionRegionSummary(finding.id(), finding.documentId(),
                finding.location().pageNumber(), finding.entityType(),
                box.left(), box.top(), box.width(), box.height());
    }

    private List<Document> manualDocuments(PackageId packageId, TenantId tenantId) {
        return documentRepository.findByPackageIdAndTenantId(packageId, tenantId).stream()
                .filter(d -> d.status() == DocumentStatus.REDACTING)
                .toList();
    }

    private DocumentPackage loadPackage(PackageId packageId, TenantId tenantId) {
        return packageRepository.findByIdAndTenantId(packageId, tenantId)
                .orElseThrow(() -> new NotFoundException("DocumentPackage", packageId.toString()));
    }

    private void requireActiveManualFallback(DocumentPackage pkg, TenantId tenantId) {
        if (pkg.status() != PackageStatus.IN_REVIEW || !manualFallbackStarted(pkg.id(), tenantId)) {
            throw new ValidationException("Package is not in business manual processing");
        }
    }

    private boolean manualFallbackStarted(PackageId packageId, TenantId tenantId) {
        return !auditQueryPort.findByFilter(new AuditRecordFilter(
                tenantId, AuditAction.BUSINESS_MANUAL_FALLBACK_STARTED.name(), null,
                "DocumentPackage", packageId.toString(), null, null, null, null, 10)).isEmpty();
    }

    private boolean hasAvailableOriginal(DocumentId documentId, TenantId tenantId) {
        return artifactRepository.findByDocumentIdAndTenantId(documentId, tenantId).stream()
                .anyMatch(a -> a.artifactType() == ArtifactType.ORIGINAL
                        && a.status() != ArtifactStatus.DELETED
                        && a.isAvailable() && a.storageReference() != null);
    }
}
