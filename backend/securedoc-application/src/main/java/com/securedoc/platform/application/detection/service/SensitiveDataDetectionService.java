package com.securedoc.platform.application.detection.service;

import com.securedoc.platform.application.detection.command.DetectSensitiveDataCommand;
import com.securedoc.platform.application.detection.result.DetectSensitiveDataResult;
import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.in.DetectSensitiveDataUseCase;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.DomainEventPublisher;
import com.securedoc.platform.application.port.out.FindingRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.SensitiveDataDetectionPort;
import com.securedoc.platform.application.port.out.SensitiveDataDetectionPort.DetectedEntity;
import com.securedoc.platform.application.port.out.SensitiveDataDetectionPort.DetectionOutput;
import com.securedoc.platform.application.port.out.SensitiveDataDetectionPort.PageInput;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.processing.service.FailureReasonSanitizer;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.document.Page;
import com.securedoc.platform.domain.event.DocumentDetectionCompletedEvent;
import com.securedoc.platform.domain.event.DocumentDetectionFailedEvent;
import com.securedoc.platform.domain.event.FindingDetectedEvent;
import com.securedoc.platform.domain.finding.DetectorSource;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.finding.FindingLocation;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.policy.PolicyDecision;
import com.securedoc.platform.domain.vo.Confidence;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Application service implementing sensitive-data detection and policy evaluation.
 *
 * <p>Flow:
 * <ol>
 *   <li>Validate invariants (document in DETECTING, package PROCESSING)</li>
 *   <li>Load extracted text from EXTRACTED_TEXT artifact</li>
 *   <li>Run detection via SensitiveDataDetectionPort</li>
 *   <li>Create and persist Finding entities</li>
 *   <li>Evaluate simple default policy</li>
 *   <li>Transition document to next state</li>
 *   <li>Publish events and audit</li>
 * </ol>
 *
 * <p>Never stores raw sensitive values in audit/outbox/events.
 * Finding.snippet stores only masked previews.
 */
public final class SensitiveDataDetectionService implements DetectSensitiveDataUseCase {

    private static final ActorId SYSTEM_ACTOR = ActorId.of(UUID.fromString("00000000-0000-0000-0000-000000000000"));

    private final DocumentRepository documentRepository;
    private final PackageRepository packageRepository;
    private final ArtifactRepository artifactRepository;
    private final StoragePort storagePort;
    private final SensitiveDataDetectionPort detectionPort;
    private final FindingRepository findingRepository;
    private final DomainEventPublisher eventPublisher;
    private final AuditService auditService;

    public SensitiveDataDetectionService(DocumentRepository documentRepository,
                                          PackageRepository packageRepository,
                                          ArtifactRepository artifactRepository,
                                          StoragePort storagePort,
                                          SensitiveDataDetectionPort detectionPort,
                                          FindingRepository findingRepository,
                                          DomainEventPublisher eventPublisher,
                                          AuditService auditService) {
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.artifactRepository = Objects.requireNonNull(artifactRepository);
        this.storagePort = Objects.requireNonNull(storagePort);
        this.detectionPort = Objects.requireNonNull(detectionPort);
        this.findingRepository = Objects.requireNonNull(findingRepository);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.auditService = Objects.requireNonNull(auditService);
    }

    @Override
    public DetectSensitiveDataResult execute(DetectSensitiveDataCommand command) {
        Objects.requireNonNull(command, "Command must not be null");

        CorrelationId correlationId = command.correlationId();

        try {
            return doDetection(command, correlationId);
        } catch (ValidationException | NotFoundException e) {
            // Precondition failure — do not mark document failed, just rethrow
            throw e;
        } catch (Exception e) {
            handleFailure(command, correlationId, e);
            throw e;
        }
    }

    private DetectSensitiveDataResult doDetection(DetectSensitiveDataCommand command,
                                                   CorrelationId correlationId) {
        // 1. Load and validate document
        Document document = documentRepository
                .findByIdAndTenantId(command.documentId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("Document", command.documentId().toString()));

        // 2. Verify document belongs to the specified package
        if (!document.packageId().equals(command.packageId())) {
            throw new ValidationException("Document does not belong to the specified package");
        }

        // 3. Verify parent package is PROCESSING
        DocumentPackage parentPackage = packageRepository
                .findByIdAndTenantId(command.packageId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("DocumentPackage", command.packageId().toString()));

        if (parentPackage.status() != PackageStatus.PROCESSING) {
            throw new ValidationException("Package is not in PROCESSING status");
        }

        // 4. Verify document is in DETECTING status
        if (document.status() != DocumentStatus.DETECTING) {
            throw new ValidationException("Document is not in DETECTING status");
        }

        // 5. Load EXTRACTED_TEXT artifact
        Artifact extractedTextArtifact = artifactRepository
                .findByDocumentIdAndTenantId(document.id(), command.tenantId())
                .stream()
                .filter(a -> a.artifactType() == ArtifactType.EXTRACTED_TEXT && a.isAvailable())
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Extracted text artifact for document",
                        command.documentId().toString()));

        // 6. Read extracted text content
        String extractedText = readExtractedText(extractedTextArtifact);

        // 7. Build page inputs from document pages (or from full text if no pages)
        List<PageInput> pageInputs = buildPageInputs(document, extractedText);

        // 8. Run detection
        DetectionOutput detectionOutput = detectionPort.detect(pageInputs);

        // 9. Create Finding domain entities from detection results
        List<Finding> findings = createFindings(detectionOutput, document);

        // 10. Persist findings
        if (!findings.isEmpty()) {
            findingRepository.saveAll(findings);
        }

        // 11. Evaluate simple default policy
        boolean requiresRedaction = detectionOutput.hasFindings();
        PolicyDecision overallDecision = requiresRedaction
                ? PolicyDecision.REDACT
                : PolicyDecision.ALLOW;

        // 12. Transition document to next state
        if (requiresRedaction) {
            // Findings exist — needs redaction in a future unit
            document.startRedaction();
        } else {
            // No findings — processing can complete
            document.completeProcessing();
        }

        // 13. Persist document state
        documentRepository.save(document);

        // 14. Publish events
        Map<String, Integer> findingsByType = buildFindingsByType(findings);

        DocumentDetectionCompletedEvent completedEvent = DocumentDetectionCompletedEvent.of(
                command.documentId(), command.packageId(), command.tenantId(),
                findings.size(), requiresRedaction, correlationId);
        eventPublisher.publish(completedEvent);

        // Publish individual FindingDetected events (IDs and type/confidence only)
        for (Finding finding : findings) {
            FindingDetectedEvent findingEvent = FindingDetectedEvent.of(
                    finding.id(), finding.documentId(), command.tenantId(),
                    finding.entityType(), finding.confidence(), correlationId);
            eventPublisher.publish(findingEvent);
        }

        // 15. Audit (safe counts only — no raw values)
        auditService.recordSuccess(
                command.tenantId(),
                SYSTEM_ACTOR,
                AuditAction.DOCUMENT_DETECTION_COMPLETED,
                "Document",
                command.documentId().toString(),
                correlationId,
                "Detection completed: findings=" + findings.size()
                        + ", requiresRedaction=" + requiresRedaction
                        + ", decision=" + overallDecision
                        + ", types=" + findingsByType
        );

        // 16. Return result
        return DetectSensitiveDataResult.of(
                command.documentId(), document.status(), findings.size(),
                findingsByType, requiresRedaction, overallDecision);
    }

    private List<Finding> createFindings(DetectionOutput output, Document document) {
        List<Finding> findings = new ArrayList<>();

        for (DetectedEntity entity : output.entities()) {
            EntityType entityType = parseEntityType(entity.entityType());
            FindingLocation location = FindingLocation.textOnly(
                    entity.pageNumber(), entity.startOffset(), entity.endOffset());
            Confidence confidence = Confidence.of(entity.confidence());
            DetectorSource source = new DetectorSource(entity.detectorName(), entity.detectorVersion());

            // snippet stores masked preview only — never raw matched value
            Finding finding = Finding.create(
                    document.tenantId(), document.id(), entityType, location,
                    confidence, source, entity.maskedPreview());

            findings.add(finding);
        }

        return findings;
    }

    private EntityType parseEntityType(String type) {
        try {
            return EntityType.valueOf(type);
        } catch (IllegalArgumentException e) {
            return EntityType.CUSTOM;
        }
    }

    private List<PageInput> buildPageInputs(Document document, String extractedText) {
        List<Page> pages = document.pages();
        if (pages.isEmpty()) {
            // Fallback: treat entire text as single page
            return List.of(new PageInput(1, extractedText));
        }

        List<PageInput> inputs = new ArrayList<>(pages.size());
        for (Page page : pages) {
            String pageText = page.text().orElse("");
            inputs.add(new PageInput(page.pageNumber(), pageText));
        }
        return inputs;
    }

    private String readExtractedText(Artifact artifact) {
        try (InputStream stream = storagePort.retrieve(artifact.storageReference())) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read extracted text artifact", e);
        }
    }

    private Map<String, Integer> buildFindingsByType(List<Finding> findings) {
        Map<String, Integer> map = new HashMap<>();
        for (Finding f : findings) {
            map.merge(f.entityType().name(), 1, Integer::sum);
        }
        return map;
    }

    private void handleFailure(DetectSensitiveDataCommand command, CorrelationId correlationId, Exception e) {
        String sanitizedReason = FailureReasonSanitizer.sanitize(e.getMessage());

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
            DocumentDetectionFailedEvent event = DocumentDetectionFailedEvent.of(
                    command.documentId(), command.packageId(), command.tenantId(),
                    sanitizedReason, correlationId);
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
                    "Detection failed: " + sanitizedReason
            );
        } catch (Exception ignored) {
            // Best-effort
        }
    }
}
