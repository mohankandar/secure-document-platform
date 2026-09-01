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
import com.securedoc.platform.application.technical.model.TechnicalExceptionDetail;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.artifact.ArtifactStatus;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Builds safe technical issue details from persisted lifecycle state plus sanitized audit metadata. */
public final class TechnicalExceptionQueryService implements GetTechnicalExceptionQuery {
    private final PackageRepository packageRepository;
    private final DocumentRepository documentRepository;
    private final PackageLifecycleRepository lifecycleRepository;
    private final ArtifactRepository artifactRepository;
    private final AuditQueryPort auditQueryPort;

    public TechnicalExceptionQueryService(PackageRepository packageRepository,
                                          DocumentRepository documentRepository,
                                          PackageLifecycleRepository lifecycleRepository,
                                          ArtifactRepository artifactRepository,
                                          AuditQueryPort auditQueryPort) {
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.lifecycleRepository = Objects.requireNonNull(lifecycleRepository);
        this.artifactRepository = Objects.requireNonNull(artifactRepository);
        this.auditQueryPort = Objects.requireNonNull(auditQueryPort);
    }

    @Override
    public TechnicalExceptionDetail execute(PackageId packageId, TenantId tenantId) {
        var pkg = packageRepository.findByIdAndTenantId(packageId, tenantId)
                .orElseThrow(() -> new NotFoundException("DocumentPackage", packageId.toString()));
        if (pkg.status() != PackageStatus.FAILED) {
            throw new IllegalStateException("Technical issue details are available only for failed packages");
        }

        List<Document> documents = documentRepository.findByPackageIdAndTenantId(packageId, tenantId);
        List<Document> failedDocuments = documents.stream()
                .filter(document -> document.status() == DocumentStatus.FAILED)
                .toList();
        List<AuditRecordSummary> packageFailures = queryFailures(tenantId, "DocumentPackage", packageId.toString(), 20);
        List<AuditRecordSummary> candidates = new ArrayList<>(packageFailures);
        boolean allFailedDocumentsRetryable = !failedDocuments.isEmpty();
        for (Document document : failedDocuments) {
            List<AuditRecordSummary> documentFailures = queryFailures(tenantId, "Document", document.id().toString(), 20);
            candidates.addAll(documentFailures);
            AuditRecordSummary latestClassified = documentFailures.stream()
                    .filter(this::containsStructuredClassification)
                    .max(Comparator.comparing(AuditRecordSummary::occurredAt))
                    .orElse(null);
            if (latestClassified == null || !TechnicalFailureAuditParser.parse(latestClassified.detail()).retryable()) {
                allFailedDocumentsRetryable = false;
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
        originalAvailable = originalAvailable && originalsPresent;

        String resourceId = selected == null ? null : selected.resourceId();
        var failedDocumentId = documents.stream()
                .filter(document -> document.status() == DocumentStatus.FAILED)
                .filter(document -> document.id().toString().equals(resourceId))
                .map(Document::id)
                .findFirst()
                .orElseGet(() -> documents.stream()
                        .filter(document -> document.status() == DocumentStatus.FAILED)
                        .map(Document::id)
                        .findFirst().orElse(null));

        boolean classifiedRetryable = failedDocuments.isEmpty()
                ? parsed.retryable()
                : allFailedDocumentsRetryable;

        String code = parsed.code();
        String dependency = parsed.dependency();
        return new TechnicalExceptionDetail(
                packageId,
                failedDocumentId,
                pkg.status().name(),
                failureCategory(code, dependency),
                failedStage(code, parsed.prefix()),
                dependency,
                code,
                classifiedRetryable && originalAvailable,
                parsed.attempts(),
                selected == null ? pkg.updatedAt() : selected.occurredAt(),
                selected == null ? null : selected.correlationId(),
                retainedUntil,
                explanation(code, dependency, classifiedRetryable, originalAvailable));
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
                limit));
    }

    private boolean containsStructuredClassification(AuditRecordSummary record) {
        return record.detail() != null && record.detail().contains("dependency=") && record.detail().contains("retryable=");
    }

    private String failedStage(String code, String prefix) {
        if (code.startsWith("AI_")) return "AI_ANALYSIS";
        if (code.startsWith("OCR_")) return "TEXT_EXTRACTION_OCR";
        if (code.startsWith("DELIVERY_")) return "DELIVERY";
        if (code.startsWith("STORAGE_")) return "STORAGE";
        String normalized = prefix == null ? "" : prefix.toLowerCase();
        if (normalized.contains("redaction")) return "REDACTION";
        if (normalized.contains("extraction")) return "TEXT_EXTRACTION";
        if (normalized.contains("delivery") || normalized.contains("export")) return "DELIVERY";
        return "DOCUMENT_PROCESSING";
    }

    private String failureCategory(String code, String dependency) {
        if (code.contains("TIMEOUT")) return "DEPENDENCY_TIMEOUT";
        if (code.contains("UNAVAILABLE")) return "DEPENDENCY_UNAVAILABLE";
        if (code.contains("RATE_LIMITED")) return "DEPENDENCY_RATE_LIMITED";
        if (code.contains("INVALID_RESPONSE") || code.contains("REJECTED")) return "DEPENDENCY_RESPONSE_ERROR";
        if (code.contains("DESTINATION_INVALID")) return "DELIVERY_CONFIGURATION";
        if (code.contains("IO") || code.contains("PROCESS_FAILED")) return dependency + "_FAILURE";
        return "TECHNICAL_PROCESSING_FAILURE";
    }

    private String explanation(String code, String dependency, boolean classifiedRetryable, boolean originalAvailable) {
        String base = switch (code) {
            case "AI_TIMEOUT" -> "The configured AI provider did not respond within the allowed time.";
            case "AI_RATE_LIMITED" -> "The configured AI provider temporarily limited requests.";
            case "AI_UNAVAILABLE" -> "The configured AI provider was unavailable during processing.";
            case "AI_REJECTED", "AI_INVALID_RESPONSE" -> "The configured AI provider returned a response that could not be safely used.";
            case "OCR_TIMEOUT" -> "OCR did not complete within the allowed time.";
            case "OCR_UNAVAILABLE" -> "The configured OCR service was unavailable.";
            case "OCR_PROCESS_FAILED" -> "OCR could not process the document successfully.";
            case "DELIVERY_IO" -> "The configured delivery destination could not be reached successfully.";
            case "DELIVERY_DESTINATION_INVALID" -> "The configured delivery destination is not valid for this package.";
            case "STORAGE_IO" -> "Internal document storage was temporarily unavailable.";
            default -> "Processing stopped because of a technical failure. Use the correlation ID for support diagnostics.";
        };
        if (classifiedRetryable && !originalAvailable) {
            return base + " Retry is unavailable because the retained original is no longer available.";
        }
        if (classifiedRetryable) {
            return base + " The failure is classified as safe to retry.";
        }
        return base;
    }
}
