package com.securedoc.platform.application.redaction.service;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.in.RedactDocumentUseCase;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.ChecksumPort;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.DomainEventPublisher;
import com.securedoc.platform.application.port.out.FindingRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.PdfRedactionPort;
import com.securedoc.platform.application.port.out.RedactionVerificationPort;
import com.securedoc.platform.application.port.out.PdfRedactionPort.RedactionOutput;
import com.securedoc.platform.application.port.out.PdfRedactionPort.RedactionTarget;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.processing.service.FailureReasonSanitizer;
import com.securedoc.platform.application.redaction.command.RedactDocumentCommand;
import com.securedoc.platform.application.redaction.result.RedactDocumentResult;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactStorageReference;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.event.RedactionCompletedEvent;
import com.securedoc.platform.domain.event.RedactionFailedEvent;
import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.finding.FindingLocation;
import com.securedoc.platform.domain.finding.FindingStatus;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.MimeType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Application service implementing document redaction.
 *
 * <p>Flow:
 * <ol>
 *   <li>Validate invariants (doc REDACTING, package PROCESSING)</li>
 *   <li>Require all DETECTED findings to be resolved by policy/review</li>
 *   <li>Build redaction targets from CONFIRMED findings only</li>
 *   <li>Allow zero targets to create a verified output while preserving untouched static pages</li>
 *   <li>Read original PDF via StoragePort</li>
 *   <li>Call PdfRedactionPort to permanently redact target pages while preserving safe untouched pages</li>
 *   <li>Verify redacted pages have no extractable text and every target is visually covered</li>
 *   <li>Store verified PDF as REDACTED artifact</li>
 *   <li>Complete document processing lifecycle (REDACTING→PROCESSED)</li>
 *   <li>Publish event and audit</li>
 * </ol>
 *
 * <p>Never stores raw sensitive values in audit/outbox/events.
 */
public final class DocumentRedactionService implements RedactDocumentUseCase {

    private static final Logger log = LoggerFactory.getLogger(DocumentRedactionService.class);
    private static final ActorId SYSTEM_ACTOR = ActorId.of(UUID.fromString("00000000-0000-0000-0000-000000000000"));
    private static final String REDACTED_MIME_TYPE = "application/pdf";

    private final DocumentRepository documentRepository;
    private final PackageRepository packageRepository;
    private final ArtifactRepository artifactRepository;
    private final FindingRepository findingRepository;
    private final StoragePort storagePort;
    private final PdfRedactionPort pdfRedactionPort;
    private final RedactionVerificationPort redactionVerificationPort;
    private final ChecksumPort checksumPort;
    private final DomainEventPublisher eventPublisher;
    private final AuditService auditService;

    public DocumentRedactionService(DocumentRepository documentRepository,
                                     PackageRepository packageRepository,
                                     ArtifactRepository artifactRepository,
                                     FindingRepository findingRepository,
                                     StoragePort storagePort,
                                     PdfRedactionPort pdfRedactionPort,
                                     RedactionVerificationPort redactionVerificationPort,
                                     ChecksumPort checksumPort,
                                     DomainEventPublisher eventPublisher,
                                     AuditService auditService) {
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.artifactRepository = Objects.requireNonNull(artifactRepository);
        this.findingRepository = Objects.requireNonNull(findingRepository);
        this.storagePort = Objects.requireNonNull(storagePort);
        this.pdfRedactionPort = Objects.requireNonNull(pdfRedactionPort);
        this.redactionVerificationPort = Objects.requireNonNull(redactionVerificationPort);
        this.checksumPort = Objects.requireNonNull(checksumPort);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.auditService = Objects.requireNonNull(auditService);
    }

    @Override
    public RedactDocumentResult execute(RedactDocumentCommand command) {
        Objects.requireNonNull(command, "Command must not be null");

        CorrelationId correlationId = command.correlationId();

        try {
            return doRedaction(command, correlationId);
        } catch (ValidationException | NotFoundException e) {
            // Precondition failures — do not mark document failed, just rethrow
            throw e;
        } catch (Exception e) {
            handleFailure(command, correlationId, e);
            throw e;
        }
    }

    private RedactDocumentResult doRedaction(RedactDocumentCommand command, CorrelationId correlationId) {
        long startedNanos = System.nanoTime();
        // 1. Load and validate document
        Document document = documentRepository
                .findByIdAndTenantId(command.documentId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("Document", command.documentId().toString()));

        // 2. Verify document belongs to the specified package
        if (!document.packageId().equals(command.packageId())) {
            throw new ValidationException("Document does not belong to the specified package");
        }

        // 3. Verify parent package is in a valid processing state
        DocumentPackage parentPackage = packageRepository
                .findByIdAndTenantId(command.packageId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("DocumentPackage", command.packageId().toString()));

        if (parentPackage.status() != PackageStatus.PROCESSING
                && parentPackage.status() != PackageStatus.PENDING_REVIEW
                && parentPackage.status() != PackageStatus.IN_REVIEW) {
            throw new ValidationException("Package is not in a valid status for redaction");
        }

        // 4. Verify document is in REDACTING status
        if (document.status() != DocumentStatus.REDACTING) {
            throw new ValidationException("Document is not in REDACTING status");
        }

        // 5. Load findings for this document (tenant-scoped). DETECTED means human review is
        //    still required; never sweep a low-confidence finding into automatic redaction.
        List<Finding> allFindings = findingRepository.findByDocumentIdAndTenantId(
                command.documentId(), command.tenantId());

        boolean hasUnreviewedFindings = allFindings.stream()
                .anyMatch(f -> f.status() == FindingStatus.DETECTED);
        if (hasUnreviewedFindings) {
            throw new ValidationException("Document has findings that still require review");
        }

        List<Finding> confirmedFindings = allFindings.stream()
                .filter(f -> f.status() == FindingStatus.CONFIRMED)
                .toList();

        // 6. Every confirmed finding must have a trusted location. Zero confirmed findings is
        //    valid: the engine still creates a verified output artifact.
        boolean allHaveBounds = confirmedFindings.stream()
                .allMatch(f -> f.location().hasBoundingBox());
        if (!allHaveBounds) {
            failDocument(command, correlationId, "Redaction location could not be determined");
            return RedactDocumentResult.of(command.documentId(), null,
                    DocumentStatus.FAILED, 0);
        }

        // 7. Build targets only from policy-confirmed findings.
        List<RedactionTarget> targets = buildRedactionTargets(confirmedFindings);

        // 9. Load original artifact
        Artifact originalArtifact = artifactRepository
                .findByDocumentIdAndTenantId(document.id(), command.tenantId())
                .stream()
                .filter(a -> a.artifactType() == ArtifactType.ORIGINAL && a.isAvailable())
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Original artifact for document",
                        command.documentId().toString()));

        // 10. Read original PDF content
        InputStream pdfContent = storagePort.retrieve(originalArtifact.storageReference());

        // 11. Perform selective permanent redaction. Target pages are burn-flattened;
        //     safe untouched pages may retain native PDF content.
        RedactionOutput redactionOutput;
        try {
            redactionOutput = pdfRedactionPort.redact(pdfContent, targets);
        } finally {
            closeQuietly(pdfContent);
        }

        // 11. Verify the generated artifact before it can be stored/exported. The expected
        //     page count comes from source extraction (Document.pages), not from the redaction
        //     adapter's own result, so a page-dropping redaction defect cannot self-validate.
        int expectedSourcePageCount = document.pageCount();
        if (expectedSourcePageCount < 1) {
            throw new IllegalStateException("Source page count is unavailable for redaction verification");
        }
        if (redactionOutput.pagesRendered() != expectedSourcePageCount) {
            throw new IllegalStateException("Redaction output page count does not match source");
        }
        if (redactionOutput.redactionsApplied() != targets.size()) {
            throw new IllegalStateException("Redaction engine did not apply every expected target");
        }

        try (var redactedContent = redactionOutput.redactedPdfContent()) {
            long redactedSize = redactedContent.contentLength();
            RedactionVerificationPort.VerificationResult verification;
            InputStream verificationStream = redactedContent.openStream();
            try {
                verification = redactionVerificationPort.verify(
                        verificationStream, expectedSourcePageCount, targets);
            } finally {
                closeQuietly(verificationStream);
            }
            if (!verification.safe()) {
                throw new IllegalStateException("Redacted output failed security verification");
            }
            auditService.recordSuccess(
                    command.tenantId(), SYSTEM_ACTOR,
                    AuditAction.DOCUMENT_REDACTION_VERIFIED, "Document", command.documentId().toString(),
                    correlationId,
                    "Redaction verified: pages=" + verification.pageCount()
                            + ", expectedRedactions=" + verification.expectedRedactionCount()
                            + ", coveredRedactions=" + verification.coveredRedactionCount()
                            + ", redactedPagesExtractableText=false");

            Checksum redactedChecksum;
            InputStream checksumStream = redactedContent.openStream();
            try {
                redactedChecksum = checksumPort.compute(checksumStream);
            } finally {
                closeQuietly(checksumStream);
            }

            Artifact redactedArtifact = Artifact.create(
                    command.tenantId(),
                    document.id(),
                    ArtifactType.REDACTED,
                    MimeType.of(REDACTED_MIME_TYPE),
                    redactedSize,
                    redactedChecksum
            );

            String storagePath = buildStoragePath(
                    command.tenantId().toString(),
                    command.packageId().toString(),
                    document.id().toString(),
                    ArtifactType.REDACTED.name(),
                    redactedArtifact.id().toString()
            );

            ArtifactStorageReference storageRef;
            InputStream storageStream = redactedContent.openStream();
            try {
                storageRef = storagePort.storeForPackage(
                        command.tenantId(), command.packageId(), storagePath, storageStream, redactedSize, REDACTED_MIME_TYPE);
            } finally {
                closeQuietly(storageStream);
            }

            redactedArtifact.markAvailable(storageRef);
            document.completeProcessing();

            for (Finding finding : confirmedFindings) {
                finding.markRedacted(SYSTEM_ACTOR);
            }
            if (!confirmedFindings.isEmpty()) {
                findingRepository.saveAll(confirmedFindings);
            }

            documentRepository.save(document);
            artifactRepository.save(redactedArtifact);

            RedactionCompletedEvent event = RedactionCompletedEvent.of(
                    command.documentId(), command.packageId(), command.tenantId(),
                    redactedArtifact.id(), redactionOutput.redactionsApplied(), correlationId);
            eventPublisher.publish(event);

            auditService.recordSuccess(
                    command.tenantId(),
                    SYSTEM_ACTOR,
                    AuditAction.DOCUMENT_REDACTION_COMPLETED,
                    "Document",
                    command.documentId().toString(),
                    correlationId,
                    "Redaction completed: redactions=" + redactionOutput.redactionsApplied()
                            + ", pages=" + redactionOutput.pagesRendered()
                            + ", artifactSize=" + redactedSize
            );

            long rasterizedTargetPages = targets.stream()
                    .map(RedactionTarget::pageNumber)
                    .distinct()
                    .count();
            log.info("DOCUMENT_REDACTION_COMPLETED document={} package={} targets={} verifiedTargets={} pages={} targetPagesRasterized={} redactedPagesExtractableText=false status={} durationMs={}",
                    command.documentId(), command.packageId(), targets.size(), verification.coveredRedactionCount(),
                    verification.pageCount(), rasterizedTargetPages, document.status(), elapsedMillis(startedNanos));

            return RedactDocumentResult.of(
                    command.documentId(), redactedArtifact.id(),
                    document.status(), redactionOutput.redactionsApplied());
        }
    }

    private List<RedactionTarget> buildRedactionTargets(List<Finding> findings) {
        List<RedactionTarget> targets = new ArrayList<>();

        for (Finding finding : findings) {
            FindingLocation location = finding.location();
            if (location.hasBoundingBox()) {
                FindingLocation.BoundingBox box = location.boundingBox();
                targets.add(RedactionTarget.of(
                        location.pageNumber(),
                        box.left(), box.top(), box.width(), box.height()));
            }
            // Findings without bounding boxes cannot be safely redacted spatially.
            // They are skipped — fail closed is handled at step 7 if ALL targets are empty.
        }

        return targets;
    }

    private void handleFailure(RedactDocumentCommand command, CorrelationId correlationId, Exception e) {
        failDocument(command, correlationId, FailureReasonSanitizer.redactionFailed());
    }

    /**
     * Marks the document as FAILED, publishes failure event, and audits.
     * Used both for safety fail-closed (no bounding boxes) and real errors.
     */
    private void failDocument(RedactDocumentCommand command, CorrelationId correlationId, String reason) {
        // Mark document failed
        try {
            documentRepository.findByIdAndTenantId(command.documentId(), command.tenantId())
                    .ifPresent(doc -> {
                        doc.fail();
                        documentRepository.save(doc);
                    });
        } catch (Exception ignored) {
            // Best-effort
        }

        // Publish failure event
        try {
            RedactionFailedEvent event = RedactionFailedEvent.of(
                    command.documentId(), command.packageId(), command.tenantId(),
                    reason, correlationId);
            eventPublisher.publish(event);
        } catch (Exception ignored) {
            // Best-effort
        }

        // Audit
        try {
            auditService.recordFailure(
                    command.tenantId(),
                    SYSTEM_ACTOR,
                    AuditAction.DOCUMENT_PROCESSING_FAILED,
                    "Document",
                    command.documentId().toString(),
                    correlationId,
                    "Redaction failed: " + reason
            );
        } catch (Exception ignored) {
            // Best-effort
        }
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private String buildStoragePath(String tenantId, String packageId, String documentId,
                                     String artifactType, String artifactId) {
        return tenantId + "/" + packageId + "/" + documentId + "/" + artifactType + "/" + artifactId;
    }

    private void closeQuietly(InputStream stream) {
        if (stream != null) {
            try { stream.close(); } catch (Exception ignored) {}
        }
    }
}
