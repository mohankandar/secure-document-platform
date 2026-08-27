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

import java.io.ByteArrayInputStream;
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
 *   <li>Load actionable findings with bounding boxes</li>
 *   <li>Build redaction targets from finding locations</li>
 *   <li>If no actionable targets: fail closed (cannot safely redact without locations)</li>
 *   <li>Read original PDF via StoragePort</li>
 *   <li>Call PdfRedactionPort to create burn-flat redacted PDF</li>
 *   <li>Store redacted PDF as REDACTED artifact</li>
 *   <li>Complete document processing lifecycle (REDACTING→PROCESSED)</li>
 *   <li>Publish event and audit</li>
 * </ol>
 *
 * <p>Never stores raw sensitive values in audit/outbox/events.
 */
public final class DocumentRedactionService implements RedactDocumentUseCase {

    private static final ActorId SYSTEM_ACTOR = ActorId.of(UUID.fromString("00000000-0000-0000-0000-000000000000"));
    private static final String REDACTED_MIME_TYPE = "application/pdf";

    private final DocumentRepository documentRepository;
    private final PackageRepository packageRepository;
    private final ArtifactRepository artifactRepository;
    private final FindingRepository findingRepository;
    private final StoragePort storagePort;
    private final PdfRedactionPort pdfRedactionPort;
    private final ChecksumPort checksumPort;
    private final DomainEventPublisher eventPublisher;
    private final AuditService auditService;

    public DocumentRedactionService(DocumentRepository documentRepository,
                                     PackageRepository packageRepository,
                                     ArtifactRepository artifactRepository,
                                     FindingRepository findingRepository,
                                     StoragePort storagePort,
                                     PdfRedactionPort pdfRedactionPort,
                                     ChecksumPort checksumPort,
                                     DomainEventPublisher eventPublisher,
                                     AuditService auditService) {
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.artifactRepository = Objects.requireNonNull(artifactRepository);
        this.findingRepository = Objects.requireNonNull(findingRepository);
        this.storagePort = Objects.requireNonNull(storagePort);
        this.pdfRedactionPort = Objects.requireNonNull(pdfRedactionPort);
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

        // 5. Load actionable findings for this document (tenant-scoped)
        List<Finding> allFindings = findingRepository.findByDocumentIdAndTenantId(
                command.documentId(), command.tenantId());

        List<Finding> actionableFindings = allFindings.stream()
                .filter(f -> f.status() == FindingStatus.DETECTED || f.status() == FindingStatus.CONFIRMED)
                .toList();

        // 6. If no actionable findings, fail closed — do not create fake redacted artifact
        if (actionableFindings.isEmpty()) {
            failDocument(command, correlationId, "No active findings available for redaction");
            return RedactDocumentResult.of(command.documentId(), null,
                    DocumentStatus.FAILED, 0);
        }

        // 7. Verify ALL actionable findings have valid bounding boxes — fail closed if any lack one
        boolean allHaveBounds = actionableFindings.stream()
                .allMatch(f -> f.location().hasBoundingBox());

        if (!allHaveBounds) {
            failDocument(command, correlationId, "Redaction location could not be determined");
            return RedactDocumentResult.of(command.documentId(), null,
                    DocumentStatus.FAILED, 0);
        }

        // 8. Build redaction targets (guaranteed all have bounding boxes at this point)
        List<RedactionTarget> targets = buildRedactionTargets(actionableFindings);

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

        // 11. Perform burn-flat redaction
        RedactionOutput redactionOutput;
        try {
            redactionOutput = pdfRedactionPort.redact(pdfContent, targets);
        } finally {
            closeQuietly(pdfContent);
        }

        // 11. Compute checksum for redacted content
        byte[] redactedBytes = redactionOutput.redactedPdfContent();
        Checksum redactedChecksum = checksumPort.compute(new ByteArrayInputStream(redactedBytes));

        // 12. Create redacted artifact entity
        Artifact redactedArtifact = Artifact.create(
                command.tenantId(),
                document.id(),
                ArtifactType.REDACTED,
                MimeType.of(REDACTED_MIME_TYPE),
                redactedBytes.length,
                redactedChecksum
        );

        // 13. Store redacted PDF (ID-only path)
        String storagePath = buildStoragePath(
                command.tenantId().toString(),
                command.packageId().toString(),
                document.id().toString(),
                ArtifactType.REDACTED.name(),
                redactedArtifact.id().toString()
        );

        ArtifactStorageReference storageRef = storagePort.store(
                command.tenantId(),
                storagePath,
                new ByteArrayInputStream(redactedBytes),
                redactedBytes.length,
                REDACTED_MIME_TYPE
        );

        // 14. Mark artifact available
        redactedArtifact.markAvailable(storageRef);

        // 15. Complete document processing (REDACTING → PROCESSED)
        document.completeProcessing();

        // 16. Mark redacted findings as REDACTED
        for (Finding finding : actionableFindings) {
            if (finding.status() == FindingStatus.DETECTED) {
                finding.confirm(SYSTEM_ACTOR, null);
            }
            finding.markRedacted(SYSTEM_ACTOR);
        }
        findingRepository.saveAll(actionableFindings);

        // 17. Persist document and artifact
        documentRepository.save(document);
        artifactRepository.save(redactedArtifact);

        // 18. Publish event
        RedactionCompletedEvent event = RedactionCompletedEvent.of(
                command.documentId(), command.packageId(), command.tenantId(),
                redactedArtifact.id(), redactionOutput.redactionsApplied(), correlationId);
        eventPublisher.publish(event);

        // 19. Audit (safe counts only)
        auditService.recordSuccess(
                command.tenantId(),
                SYSTEM_ACTOR,
                AuditAction.DOCUMENT_REDACTION_COMPLETED,
                "Document",
                command.documentId().toString(),
                correlationId,
                "Redaction completed: redactions=" + redactionOutput.redactionsApplied()
                        + ", pages=" + redactionOutput.pagesRendered()
                        + ", artifactSize=" + redactedBytes.length
        );

        return RedactDocumentResult.of(
                command.documentId(), redactedArtifact.id(),
                document.status(), redactionOutput.redactionsApplied());
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
        String sanitizedReason = FailureReasonSanitizer.sanitize(e.getMessage());
        failDocument(command, correlationId, sanitizedReason);
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
