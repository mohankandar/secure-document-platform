package com.securedoc.platform.application.technical.service;

import com.securedoc.platform.application.audit.query.AuditRecordFilter;
import com.securedoc.platform.application.audit.query.AuditRecordSummary;
import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.port.in.GetTechnicalExceptionQuery;
import com.securedoc.platform.application.port.out.AuditQueryPort;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.PackageLifecycleRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.ProcessingIncidentRepository;
import com.securedoc.platform.application.technical.model.TechnicalExceptionDetail;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.artifact.ArtifactStatus;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.processing.ProcessingIncident;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Builds safe technical issue details from durable structured incidents when available, while
 * retaining audit-backed compatibility for incidents created before the structured incident store.
 */
public final class TechnicalExceptionQueryService implements GetTechnicalExceptionQuery {
    private final PackageRepository packageRepository;
    private final DocumentRepository documentRepository;
    private final PackageLifecycleRepository lifecycleRepository;
    private final ArtifactRepository artifactRepository;
    private final AuditQueryPort auditQueryPort;
    private final ProcessingIncidentRepository incidentRepository;

    public TechnicalExceptionQueryService(PackageRepository packageRepository,
                                          DocumentRepository documentRepository,
                                          PackageLifecycleRepository lifecycleRepository,
                                          ArtifactRepository artifactRepository,
                                          AuditQueryPort auditQueryPort) {
        this(packageRepository, documentRepository, lifecycleRepository, artifactRepository,
                auditQueryPort, ProcessingIncidentRepository.none());
    }

    public TechnicalExceptionQueryService(PackageRepository packageRepository,
                                          DocumentRepository documentRepository,
                                          PackageLifecycleRepository lifecycleRepository,
                                          ArtifactRepository artifactRepository,
                                          AuditQueryPort auditQueryPort,
                                          ProcessingIncidentRepository incidentRepository) {
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.lifecycleRepository = Objects.requireNonNull(lifecycleRepository);
        this.artifactRepository = Objects.requireNonNull(artifactRepository);
        this.auditQueryPort = Objects.requireNonNull(auditQueryPort);
        this.incidentRepository = Objects.requireNonNull(incidentRepository);
    }

    @Override
    public TechnicalExceptionDetail execute(PackageId packageId, TenantId tenantId) {
        var pkg = packageRepository.findByIdAndTenantId(packageId, tenantId)
                .orElseThrow(() -> new NotFoundException("DocumentPackage", packageId.toString()));
        List<Document> documents = documentRepository.findByPackageIdAndTenantId(packageId, tenantId);
        RetentionState retention = retentionState(packageId, tenantId, documents);

        var structured = incidentRepository.findLatestByPackageIdAndTenantId(packageId, tenantId);
        if (structured.isPresent()) {
            return fromStructuredIncident(structured.get(), pkg.status(), retention);
        }

        return fromLegacyAudit(packageId, tenantId, pkg.status(), pkg.updatedAt(), documents, retention);
    }

    private TechnicalExceptionDetail fromStructuredIncident(ProcessingIncident incident,
                                                             PackageStatus packageStatus,
                                                             RetentionState retention) {
        boolean retryAvailable = packageStatus == PackageStatus.FAILED
                && incident.retryable() && retention.originalAvailable();
        var events = incidentRepository.findEvents(incident.id(), incident.tenantId(), 50).stream()
                .sorted(Comparator.comparing(com.securedoc.platform.domain.processing.ProcessingIncidentEvent::occurredAt))
                .map(event -> new TechnicalExceptionDetail.IncidentEventSummary(
                        event.eventType().name(), event.attemptNumber(), event.scope(), event.reasonCode(),
                        event.retryable(), event.correlationId().toString(), event.occurredAt(), event.safeMetadata()))
                .toList();
        return new TechnicalExceptionDetail(
                incident.packageId(), incident.documentId(), packageStatus.name(), incident.failureCategory(),
                incident.stage().name(), incident.component(), incident.reasonCode(), retryAvailable,
                incident.attempts(), incident.firstObservedAt(), incident.correlationId().toString(),
                retention.retainedUntil(), TechnicalFailureTaxonomy.explanation(
                        incident.reasonCode(), incident.retryable(), retention.originalAvailable(), packageStatus == PackageStatus.FAILED),
                incident.id(), incident.status().name(), incident.businessContinuityUsed(),
                incident.businessOutcome(), incident.processingFingerprint(), events);
    }

    private TechnicalExceptionDetail fromLegacyAudit(PackageId packageId,
                                                      TenantId tenantId,
                                                      PackageStatus packageStatus,
                                                      Instant packageUpdatedAt,
                                                      List<Document> documents,
                                                      RetentionState retention) {
        List<AuditRecordSummary> packageFailures = queryFailures(tenantId, "DocumentPackage", packageId.toString(), 20);
        if (packageStatus != PackageStatus.FAILED && packageFailures.isEmpty()) {
            boolean anyDocumentFailure = documents.stream().anyMatch(document ->
                    !queryFailures(tenantId, "Document", document.id().toString(), 1).isEmpty());
            if (!anyDocumentFailure) {
                throw new IllegalStateException("No recorded technical issue is available for this package");
            }
        }

        List<Document> failedDocuments = documents.stream()
                .filter(document -> document.status() == DocumentStatus.FAILED)
                .toList();
        List<AuditRecordSummary> candidates = new ArrayList<>(packageFailures);
        boolean allFailedDocumentsRetryable = !failedDocuments.isEmpty();
        for (Document document : documents) {
            List<AuditRecordSummary> documentFailures = queryFailures(tenantId, "Document", document.id().toString(), 20);
            candidates.addAll(documentFailures);
            if (document.status() == DocumentStatus.FAILED) {
                AuditRecordSummary latestClassified = documentFailures.stream()
                        .filter(this::containsStructuredClassification)
                        .max(Comparator.comparing(AuditRecordSummary::occurredAt))
                        .orElse(null);
                if (latestClassified == null || !TechnicalFailureAuditParser.parse(latestClassified.detail()).retryable()) {
                    allFailedDocumentsRetryable = false;
                }
            }
        }

        AuditRecordSummary selected = candidates.stream()
                .filter(this::containsStructuredClassification)
                .max(Comparator.comparing(AuditRecordSummary::occurredAt))
                .orElseGet(() -> candidates.stream()
                        .max(Comparator.comparing(AuditRecordSummary::occurredAt))
                        .orElse(null));

        TechnicalFailureAuditParser.Parsed parsed = selected == null
                ? TechnicalFailureAuditParser.Parsed.unknown()
                : TechnicalFailureAuditParser.parse(selected.detail());

        String resourceId = selected == null ? null : selected.resourceId();
        var failedDocumentId = documents.stream()
                .filter(document -> document.id().toString().equals(resourceId))
                .map(Document::id)
                .findFirst()
                .orElseGet(() -> failedDocuments.stream().map(Document::id).findFirst().orElse(null));
        if (failedDocumentId == null) {
            failedDocumentId = documents.stream()
                    .filter(document -> !queryFailures(tenantId, "Document", document.id().toString(), 1).isEmpty())
                    .map(Document::id).findFirst().orElse(null);
        }

        boolean classifiedRetryable = failedDocuments.isEmpty()
                ? parsed.retryable()
                : allFailedDocumentsRetryable;
        String code = parsed.code();
        String dependency = parsed.dependency();
        boolean retryAvailable = packageStatus == PackageStatus.FAILED && classifiedRetryable && retention.originalAvailable();
        return new TechnicalExceptionDetail(
                packageId,
                failedDocumentId,
                packageStatus.name(),
                TechnicalFailureTaxonomy.category(code, dependency),
                failedStage(code, parsed.prefix()),
                dependency,
                code,
                retryAvailable,
                parsed.attempts(),
                selected == null ? packageUpdatedAt : selected.occurredAt(),
                selected == null ? null : selected.correlationId(),
                retention.retainedUntil(),
                TechnicalFailureTaxonomy.explanation(code, classifiedRetryable, retention.originalAvailable(), packageStatus == PackageStatus.FAILED));
    }

    private RetentionState retentionState(PackageId packageId, TenantId tenantId, List<Document> documents) {
        var lifecycle = lifecycleRepository.findByPackageIdAndTenantId(packageId, tenantId).orElse(null);
        Instant retainedUntil = null;
        boolean originalAvailable = lifecycle != null
                && lifecycle.originalPurgedAt().isEmpty()
                && lifecycle.purgedAt().isEmpty();
        if (lifecycle != null) {
            retainedUntil = lifecycle.successfulExportCycles() > 0
                    ? lifecycle.originalDeleteAt().orElse(null)
                    : lifecycle.technicalHoldUntil().orElse(null);
            if (retainedUntil != null && !Instant.now().isBefore(retainedUntil)) {
                originalAvailable = false;
            }
        }

        boolean originalsPresent = !documents.isEmpty() && documents.stream().allMatch(document ->
                artifactRepository.findByDocumentIdAndTenantId(document.id(), tenantId).stream()
                        .anyMatch(artifact -> artifact.artifactType() == ArtifactType.ORIGINAL
                                && artifact.status() != ArtifactStatus.DELETED));
        return new RetentionState(retainedUntil, originalAvailable && originalsPresent);
    }

    private List<AuditRecordSummary> queryFailures(TenantId tenantId, String resourceType, String resourceId, int limit) {
        return auditQueryPort.findByFilter(new AuditRecordFilter(
                tenantId,
                AuditAction.DOCUMENT_PROCESSING_FAILED.name(),
                null,
                resourceType,
                resourceId,
                null,
                null,
                null,
                null,
                limit, true));
    }

    private boolean containsStructuredClassification(AuditRecordSummary record) {
        return record.detail() != null && record.detail().contains("dependency=") && record.detail().contains("retryable=");
    }

    private String failedStage(String code, String prefix) {
        if (code.startsWith("AI_")) return "AI_ANALYSIS";
        if (code.startsWith("OCR_")) return "OCR";
        if (code.startsWith("DELIVERY_")) return "DELIVERY";
        if (code.startsWith("STORAGE_")) return "STORAGE";
        String normalized = prefix == null ? "" : prefix.toLowerCase();
        if (normalized.contains("redaction")) return "REDACTION";
        if (normalized.contains("extraction")) return "TEXT_EXTRACTION";
        if (normalized.contains("delivery") || normalized.contains("export")) return "DELIVERY";
        return "DOCUMENT_PROCESSING";
    }

    private record RetentionState(Instant retainedUntil, boolean originalAvailable) { }
}
