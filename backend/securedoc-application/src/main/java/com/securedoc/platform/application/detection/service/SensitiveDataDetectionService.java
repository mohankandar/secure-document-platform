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
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.DiscoveredSensitiveContent;
import com.securedoc.platform.application.port.out.SensitiveDataDetectionPort;
import com.securedoc.platform.application.port.out.SensitiveDataDetectionPort.DetectedEntity;
import com.securedoc.platform.application.port.out.SensitiveDataDetectionPort.DetectionOutput;
import com.securedoc.platform.application.port.out.SensitiveDataDetectionPort.PageInput;
import com.securedoc.platform.application.port.out.SensitiveDataValidationPort;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.application.port.out.SensitiveDataValidationPort.Candidate;
import com.securedoc.platform.application.port.out.SensitiveDataValidationPort.ValidationEvidence;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.port.out.TextExtractionPort;
import com.securedoc.platform.application.port.out.TextExtractionPort.ExtractionSource;
import com.securedoc.platform.application.port.out.TextExtractionPort.PageText;
import com.securedoc.platform.application.port.out.TextExtractionPort.TextExtractionOutput;
import com.securedoc.platform.application.processing.service.FailureReasonSanitizer;
import com.securedoc.platform.application.resilience.TechnicalFailureClassifier;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.business.ReviewMode;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.event.DocumentDetectionCompletedEvent;
import com.securedoc.platform.domain.event.DocumentDetectionFailedEvent;
import com.securedoc.platform.domain.event.FindingDetectedEvent;
import com.securedoc.platform.domain.finding.DetectorSource;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.finding.FindingLocation;
import com.securedoc.platform.domain.finding.FindingStatus;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.policy.PolicyDecision;
import com.securedoc.platform.domain.vo.Confidence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * AI-first sensitive-content discovery and policy evaluation.
 *
 * <p>Every text-bearing document is independently analyzed by the semantic/AI port.
 * Deterministic recognizers run in parallel as supporting evidence and validation. The
 * two result sets are merged before confidence policy is applied; deterministic discovery
 * never gates whether AI gets a chance to find content.
 */
public final class SensitiveDataDetectionService implements DetectSensitiveDataUseCase {

    private static final Logger log = LoggerFactory.getLogger(SensitiveDataDetectionService.class);
    private static final ActorId SYSTEM_ACTOR = ActorId.of(UUID.fromString("00000000-0000-0000-0000-000000000000"));
    private static final double AGREEMENT_CONFIDENCE_BOOST = 0.05;

    private final DocumentRepository documentRepository;
    private final PackageRepository packageRepository;
    private final ArtifactRepository artifactRepository;
    private final StoragePort storagePort;
    private final SensitiveContentAnalysisPort semanticAnalysisPort;
    private final SensitiveDataDetectionPort deterministicDetectionPort;
    private final SensitiveDataValidationPort deterministicValidationPort;
    private final TenantRedactionPolicyRepository tenantRedactionPolicyRepository;
    private final FindingEvidenceConfidencePolicy evidenceConfidencePolicy = new FindingEvidenceConfidencePolicy();
    private final TextExtractionPort textExtractionPort;
    private final FindingRepository findingRepository;
    private final DomainEventPublisher eventPublisher;
    private final AuditService auditService;

    public SensitiveDataDetectionService(DocumentRepository documentRepository,
                                         PackageRepository packageRepository,
                                         ArtifactRepository artifactRepository,
                                         StoragePort storagePort,
                                         SensitiveContentAnalysisPort semanticAnalysisPort,
                                         SensitiveDataDetectionPort deterministicDetectionPort,
                                         SensitiveDataValidationPort deterministicValidationPort,
                                         TenantRedactionPolicyRepository tenantRedactionPolicyRepository,
                                         TextExtractionPort textExtractionPort,
                                         FindingRepository findingRepository,
                                         DomainEventPublisher eventPublisher,
                                         AuditService auditService) {
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.artifactRepository = Objects.requireNonNull(artifactRepository);
        this.storagePort = Objects.requireNonNull(storagePort);
        this.semanticAnalysisPort = Objects.requireNonNull(semanticAnalysisPort);
        this.deterministicDetectionPort = Objects.requireNonNull(deterministicDetectionPort);
        this.deterministicValidationPort = Objects.requireNonNull(deterministicValidationPort);
        this.tenantRedactionPolicyRepository = Objects.requireNonNull(tenantRedactionPolicyRepository);
        this.textExtractionPort = Objects.requireNonNull(textExtractionPort);
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
            throw e;
        } catch (Exception e) {
            handleFailure(command, correlationId, e);
            throw e;
        }
    }

    private DetectSensitiveDataResult doDetection(DetectSensitiveDataCommand command,
                                                   CorrelationId correlationId) {
        long startedNanos = System.nanoTime();
        Document document = documentRepository
                .findByIdAndTenantId(command.documentId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("Document", command.documentId().toString()));

        if (!document.packageId().equals(command.packageId())) {
            throw new ValidationException("Document does not belong to the specified package");
        }

        DocumentPackage parentPackage = packageRepository
                .findByIdAndTenantId(command.packageId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("DocumentPackage", command.packageId().toString()));
        if (parentPackage.status() != PackageStatus.PROCESSING) {
            throw new ValidationException("Package is not in PROCESSING status");
        }
        if (document.status() != DocumentStatus.DETECTING) {
            throw new ValidationException("Document is not in DETECTING status");
        }

        TenantRedactionPolicy tenantPolicy = tenantRedactionPolicyRepository.findByTenantId(command.tenantId())
                .orElseGet(() -> TenantRedactionPolicy.defaults(command.tenantId()));
        Set<EntityType> enabledTypes = tenantPolicy.enabledEntityTypes();

        // Trusted positional text is the common normalization layer for both semantic discovery
        // and deterministic recognition. Unit 17 will feed OCR text/coordinates into the same shape.
        Map<Integer, PageText> pagesByNumber = extractPagePositions(document, command.tenantId());
        List<PageInput> deterministicInputs = buildDeterministicInputs(pagesByNumber);
        List<SensitiveContentAnalysisPort.PageContent> semanticInputs = buildSemanticInputs(pagesByNumber);

        // AI-first: semantic analysis always runs independently for every text-bearing document.
        // Truly blank pages were already safely inspected by native extraction/OCR and do not need
        // a meaningless model call.
        boolean hasTextBearingPage = pagesByNumber.values().stream().anyMatch(page -> !page.text().isBlank());
        SensitiveContentAnalysisPort.AnalysisPolicy analysisPolicy =
                new SensitiveContentAnalysisPort.AnalysisPolicy(enabledTypes, tenantPolicy.maskSsnLastFour());
        SensitiveContentAnalysisPort.AnalysisOutput semanticOutput = hasTextBearingPage
                ? semanticAnalysisPort.analyze(new SensitiveContentAnalysisPort.AnalysisRequest(
                        command.tenantId(), semanticInputs, analysisPolicy))
                : new SensitiveContentAnalysisPort.AnalysisOutput(List.of());
        DetectionOutput deterministicOutput = hasTextBearingPage
                ? deterministicDetectionPort.detect(deterministicInputs)
                : new DetectionOutput(List.of());

        SemanticResolution semanticResolution = resolveSemanticDiscoveries(semanticOutput.findings(), pagesByNumber, enabledTypes, tenantPolicy.maskSsnLastFour());
        List<DetectedEntity> mergedEntities = mergeEvidence(semanticResolution.located(), deterministicOutput.entities(), enabledTypes);
        List<Finding> findings = createFindings(new DetectionOutput(mergedEntities), document, pagesByNumber, enabledTypes);
        findings.addAll(createUnlocatedSemanticFindings(semanticResolution.unlocated(), document));

        // Per-finding policy. Only findings that individually meet confidence AND have a trusted
        // location are auto-confirmed. Low-confidence/unlocated findings remain DETECTED for review.
        for (Finding finding : findings) {
            boolean autoConfirmAllowed = tenantPolicy.reviewMode() == ReviewMode.UNCERTAIN_ONLY;
            if (autoConfirmAllowed
                    && finding.confidence().value() >= tenantPolicy.autoRedactThreshold()
                    && finding.location().hasBoundingBox()) {
                finding.confirm(SYSTEM_ACTOR, "Auto-confirmed by tenant confidence policy");
            }
        }
        if (!findings.isEmpty()) {
            findingRepository.saveAll(findings);
        }

        boolean requiresReview = findings.stream().anyMatch(f -> f.status() == FindingStatus.DETECTED);
        boolean requiresRedaction = findings.stream().anyMatch(f -> f.status() == FindingStatus.CONFIRMED);

        PolicyDecision overallDecision = requiresReview
                ? PolicyDecision.REVIEW
                : (requiresRedaction ? PolicyDecision.REDACT : PolicyDecision.ALLOW);

        // REDACTING is also the safe-output preparation state. With no findings, the redaction
        // engine burn-flattens the PDF with zero targets so the customer still receives output.
        // With review findings, the document remains here until only confirmed/dismissed items remain.
        document.startRedaction();
        documentRepository.save(document);

        Map<String, Integer> findingsByType = buildFindingsByType(findings);
        Map<String, List<Integer>> findingPagesByType = buildFindingPagesByType(findings);
        long autoConfirmedCount = findings.stream().filter(f -> f.status() == FindingStatus.CONFIRMED).count();
        log.info("FINDING_EVIDENCE_SUMMARY document={} semanticRaw={} semanticLocated={} semanticUnlocated={} deterministic={} merged={} semanticByType={} deterministicByType={}",
                command.documentId(), semanticOutput.findings().size(), semanticResolution.located().size(),
                semanticResolution.unlocated().size(), deterministicOutput.entities().size(), mergedEntities.size(),
                buildDiscoveryCounts(semanticOutput.findings()), buildDetectedEntityCounts(deterministicOutput.entities()));
        log.info("DOCUMENT_DETECTION_COMPLETED document={} package={} findings={} byType={} pagesByType={} autoConfirmed={} reviewRequired={} decision={} enabledTypes={} maskSsnLastFour={} durationMs={}",
                command.documentId(), command.packageId(), findings.size(), findingsByType, findingPagesByType,
                autoConfirmedCount, requiresReview, overallDecision, enabledTypes, tenantPolicy.maskSsnLastFour(),
                elapsedMillis(startedNanos));

        eventPublisher.publish(DocumentDetectionCompletedEvent.of(
                command.documentId(), command.packageId(), command.tenantId(),
                findings.size(), requiresRedaction, correlationId));

        for (Finding finding : findings) {
            eventPublisher.publish(FindingDetectedEvent.of(
                    finding.id(), finding.documentId(), command.tenantId(),
                    finding.entityType(), finding.confidence(), correlationId));
        }

        auditService.recordSuccess(
                command.tenantId(), SYSTEM_ACTOR,
                AuditAction.DOCUMENT_DETECTION_COMPLETED,
                "Document", command.documentId().toString(), correlationId,
                "Detection completed: findings=" + findings.size()
                        + ", autoConfirmed=" + findings.stream().filter(f -> f.status() == FindingStatus.CONFIRMED).count()
                        + ", requiresReview=" + requiresReview
                        + ", threshold=" + tenantPolicy.autoRedactThreshold()
                        + ", reviewMode=" + tenantPolicy.reviewMode()
                        + ", enabledTypes=" + enabledTypes.size()
                        + ", unlocatedSemantic=" + semanticResolution.unlocated().size()
                        + ", decision=" + overallDecision
                        + ", types=" + findingsByType);

        return DetectSensitiveDataResult.of(
                command.documentId(), document.status(), findings.size(),
                findingsByType, requiresRedaction, overallDecision);
    }

    private SemanticResolution resolveSemanticDiscoveries(List<DiscoveredSensitiveContent> discoveries,
                                                          Map<Integer, PageText> pagesByNumber,
                                                          Set<EntityType> enabledTypes,
                                                          boolean maskSsnLastFour) {
        List<DetectedEntity> located = new ArrayList<>();
        List<UnlocatedSemanticFinding> unlocated = new ArrayList<>();

        for (DiscoveredSensitiveContent discovery : discoveries) {
            EntityType type = parseEntityType(discovery.entityType());
            if (!enabledTypes.contains(type)) {
                continue;
            }
            boolean ssnLastFour = type == EntityType.SSN && isSsnLastFourDiscovery(discovery);
            if (type == EntityType.SSN && !isExposedSsnValue(discovery)) {
                // Model contract guardrail: a conceptual mention such as "Social Security number"
                // contains no exposed SSN value and therefore has nothing to redact.
                continue;
            }
            if (ssnLastFour && !maskSsnLastFour) {
                continue;
            }
            String matchedText = ssnLastFour
                    ? discovery.matchedText().replaceAll("[^0-9]", "")
                    : discovery.matchedText();
            PageText page = pagesByNumber.get(discovery.pageNumber());
            if (page == null || page.text().isBlank()) {
                continue;
            }

            ValidationEvidence validation = deterministicValidationPort.validate(new Candidate(
                    discovery.entityType(), matchedText, discovery.contextText()));
            double effectiveConfidence = evidenceConfidencePolicy.effectiveSemanticConfidence(
                    discovery.confidence(), validation.outcome());
            String analyzerVersion = discovery.analyzerVersion() + ";validation=" + validation.outcome();

            int[] offsets = resolveOffsets(page.text(), matchedText, discovery.contextText());
            if (offsets == null) {
                // Never silently discard a semantic finding. If the AI identifies sensitive content
                // but trusted extraction cannot select one exact occurrence, preserve it as an
                // unlocated page-level finding. No bounding box means it cannot auto-redact and
                // therefore routes to focused review.
                unlocated.add(new UnlocatedSemanticFinding(
                        type, discovery.pageNumber(), effectiveConfidence,
                        discovery.analyzerName(), analyzerVersion));
                continue;
            }

            located.add(new DetectedEntity(
                    discovery.entityType(), discovery.pageNumber(), offsets[0], offsets[1],
                    effectiveConfidence, "***", discovery.analyzerName(), analyzerVersion));
        }
        return new SemanticResolution(located, unlocated);
    }

    private boolean isExposedSsnValue(DiscoveredSensitiveContent discovery) {
        String digits = discovery.matchedText().replaceAll("[^0-9]", "");
        if (digits.length() == 9) {
            return true;
        }
        return digits.length() == 4 && isSsnLastFourDiscovery(discovery);
    }

    /** Tenant policy can disable last-four-only SSN masking without disabling full SSN masking. */
    private boolean isSsnLastFourDiscovery(DiscoveredSensitiveContent discovery) {
        String digits = discovery.matchedText().replaceAll("[^0-9]", "");
        if (digits.length() != 4) {
            return false;
        }
        String context = discovery.contextText() == null
                ? ""
                : discovery.contextText().toLowerCase(java.util.Locale.ROOT);
        return context.matches("(?s).*\\b(?:ssn|social\\s+security(?:\\s+number)?)\\b.*");
    }

    private List<Finding> createUnlocatedSemanticFindings(List<UnlocatedSemanticFinding> unlocated,
                                                           Document document) {
        List<Finding> findings = new ArrayList<>(unlocated.size());
        for (UnlocatedSemanticFinding candidate : unlocated) {
            findings.add(Finding.create(
                    document.tenantId(), document.id(), candidate.entityType(),
                    FindingLocation.unlocated(candidate.pageNumber()),
                    Confidence.of(candidate.confidence()),
                    new DetectorSource(candidate.detectorName(), candidate.detectorVersion()),
                    "***"));
        }
        return findings;
    }

    private record SemanticResolution(List<DetectedEntity> located,
                                      List<UnlocatedSemanticFinding> unlocated) {
        private SemanticResolution {
            located = List.copyOf(located);
            unlocated = List.copyOf(unlocated);
        }
    }

    private record UnlocatedSemanticFinding(EntityType entityType, int pageNumber, double confidence,
                                             String detectorName, String detectorVersion) {
    }

    /** Resolves an AI text discovery against trusted page text; never trusts model X/Y coordinates. */
    private int[] resolveOffsets(String pageText, String matchedText, String contextText) {
        if (contextText != null && !contextText.isBlank()) {
            int contextStart = uniqueIndexOf(pageText, contextText);
            int inside = contextText.indexOf(matchedText);
            if (contextStart >= 0 && inside >= 0) {
                int start = contextStart + inside;
                return new int[]{start, start + matchedText.length()};
            }
        }

        int matchStart = uniqueIndexOf(pageText, matchedText);
        if (matchStart >= 0) {
            return new int[]{matchStart, matchStart + matchedText.length()};
        }
        return null;
    }

    /** Returns the index only when the needle occurs exactly once. */
    private int uniqueIndexOf(String text, String needle) {
        int first = text.indexOf(needle);
        if (first < 0) {
            return -1;
        }
        return text.indexOf(needle, first + 1) < 0 ? first : -1;
    }

    /**
     * Merges semantic and deterministic evidence for the same page/type/span. AI-only and
     * deterministic-only findings remain valid. Agreement provides a small confidence boost.
     */
    private List<DetectedEntity> mergeEvidence(List<DetectedEntity> semantic,
                                               List<DetectedEntity> deterministic,
                                               Set<EntityType> enabledTypes) {
        List<DetectedEntity> all = new ArrayList<>();
        all.addAll(semantic);
        deterministic.stream()
                .filter(e -> enabledTypes.contains(parseEntityType(e.entityType())))
                .forEach(all::add);

        all.sort(Comparator
                .comparingInt(DetectedEntity::pageNumber)
                .thenComparingInt(DetectedEntity::startOffset)
                .thenComparingInt(DetectedEntity::endOffset));

        List<DetectedEntity> merged = new ArrayList<>();
        for (DetectedEntity candidate : all) {
            int existingIndex = findOverlappingSameType(merged, candidate);
            if (existingIndex < 0) {
                merged.add(candidate);
                continue;
            }

            DetectedEntity existing = merged.get(existingIndex);
            boolean independentAgreement = !Objects.equals(existing.detectorName(), candidate.detectorName());
            double confidence = Math.max(existing.confidence(), candidate.confidence());
            if (independentAgreement) {
                confidence = Math.min(1.0, confidence + AGREEMENT_CONFIDENCE_BOOST);
            }

            String preview = !"***".equals(existing.maskedPreview())
                    ? existing.maskedPreview() : candidate.maskedPreview();
            String sourceName = independentAgreement
                    ? existing.detectorName() + "+" + candidate.detectorName()
                    : existing.detectorName();

            merged.set(existingIndex, new DetectedEntity(
                    existing.entityType(), existing.pageNumber(),
                    Math.min(existing.startOffset(), candidate.startOffset()),
                    Math.max(existing.endOffset(), candidate.endOffset()),
                    confidence, preview, sourceName, "merged-v1"));
        }
        return merged;
    }

    private int findOverlappingSameType(List<DetectedEntity> entities, DetectedEntity candidate) {
        EntityType candidateType = parseEntityType(candidate.entityType());
        for (int i = 0; i < entities.size(); i++) {
            DetectedEntity existing = entities.get(i);
            if (existing.pageNumber() != candidate.pageNumber()) {
                continue;
            }
            if (parseEntityType(existing.entityType()) != candidateType) {
                continue;
            }
            boolean overlaps = existing.startOffset() < candidate.endOffset()
                    && candidate.startOffset() < existing.endOffset();
            if (overlaps) {
                return i;
            }
        }
        return -1;
    }

    private List<Finding> createFindings(DetectionOutput output, Document document,
                                         Map<Integer, PageText> pagesByNumber,
                                         Set<EntityType> enabledTypes) {
        List<Finding> findings = new ArrayList<>();
        for (DetectedEntity entity : output.entities()) {
            EntityType entityType = parseEntityType(entity.entityType());
            if (!enabledTypes.contains(entityType)) {
                continue;
            }

            FindingLocation location = FindingLocation.textOnly(
                    entity.pageNumber(), entity.startOffset(), entity.endOffset());
            PageText page = pagesByNumber.get(entity.pageNumber());
            if (page != null) {
                var box = FindingLocationMapper.resolveBoundingBox(
                        page, entity.startOffset(), entity.endOffset());
                if (box.isPresent()) {
                    location = FindingLocation.withBounds(
                            entity.pageNumber(), entity.startOffset(), entity.endOffset(), box.get());
                }
            }

            double effectiveConfidence = entity.confidence();
            if (page != null && page.source() == ExtractionSource.OCR) {
                // OCR recognition quality is trusted evidence about whether the located glyphs
                // actually represent the text the AI/deterministic layer analyzed. Low OCR
                // confidence must be able to force focused review instead of irreversible redaction.
                double locationConfidence = FindingLocationMapper.resolveSpanConfidence(
                                page, entity.startOffset(), entity.endOffset())
                        .orElse(page.sourceConfidence());
                effectiveConfidence = Math.min(effectiveConfidence, locationConfidence);
            }

            findings.add(Finding.create(
                    document.tenantId(), document.id(), entityType, location,
                    Confidence.of(effectiveConfidence),
                    new DetectorSource(entity.detectorName(), entity.detectorVersion()),
                    entity.maskedPreview()));
        }
        return findings;
    }

    private Map<Integer, PageText> extractPagePositions(Document document,
                                                        com.securedoc.platform.domain.identity.TenantId tenantId) {
        Artifact originalArtifact = artifactRepository
                .findByDocumentIdAndTenantId(document.id(), tenantId)
                .stream()
                .filter(a -> a.artifactType() == ArtifactType.ORIGINAL && a.isAvailable())
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Original artifact for document",
                        document.id().toString()));

        Map<Integer, PageText> byNumber = new LinkedHashMap<>();
        try (InputStream stream = storagePort.retrieve(originalArtifact.storageReference())) {
            TextExtractionOutput output = textExtractionPort.extract(stream, "application/pdf");
            output.pages().stream()
                    .sorted(Comparator.comparingInt(PageText::pageNumber))
                    .forEach(page -> byNumber.put(page.pageNumber(), page));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to extract positional page data", e);
        }
        return byNumber;
    }

    private EntityType parseEntityType(String type) {
        if (type == null) {
            return EntityType.CUSTOM;
        }
        String normalized = type.trim().toUpperCase();
        if ("CREDIT_CARD".equals(normalized)) {
            return EntityType.CREDIT_CARD_NUMBER;
        }
        try {
            return EntityType.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return EntityType.CUSTOM;
        }
    }

    private List<PageInput> buildDeterministicInputs(Map<Integer, PageText> pagesByNumber) {
        List<PageInput> inputs = new ArrayList<>(pagesByNumber.size());
        pagesByNumber.values().forEach(page -> inputs.add(new PageInput(page.pageNumber(), page.text())));
        if (inputs.isEmpty()) {
            inputs.add(new PageInput(1, ""));
        }
        return inputs;
    }

    private List<SensitiveContentAnalysisPort.PageContent> buildSemanticInputs(Map<Integer, PageText> pagesByNumber) {
        List<SensitiveContentAnalysisPort.PageContent> inputs = new ArrayList<>(pagesByNumber.size());
        pagesByNumber.values().forEach(page -> inputs.add(
                new SensitiveContentAnalysisPort.PageContent(page.pageNumber(), page.text())));
        if (inputs.isEmpty()) {
            inputs.add(new SensitiveContentAnalysisPort.PageContent(1, ""));
        }
        return inputs;
    }

    private Map<String, List<Integer>> buildFindingPagesByType(List<Finding> findings) {
        Map<String, java.util.TreeSet<Integer>> pages = new java.util.TreeMap<>();
        for (Finding finding : findings) {
            pages.computeIfAbsent(finding.entityType().name(), ignored -> new java.util.TreeSet<>())
                    .add(finding.location().pageNumber());
        }
        Map<String, List<Integer>> result = new java.util.LinkedHashMap<>();
        pages.forEach((type, pageNumbers) -> result.put(type, List.copyOf(pageNumbers)));
        return result;
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private Map<String, Integer> buildFindingsByType(List<Finding> findings) {
        Map<String, Integer> map = new HashMap<>();
        for (Finding finding : findings) {
            map.merge(finding.entityType().name(), 1, Integer::sum);
        }
        return map;
    }


    private Map<String, Integer> buildDiscoveryCounts(List<DiscoveredSensitiveContent> discoveries) {
        Map<String, Integer> map = new java.util.TreeMap<>();
        for (DiscoveredSensitiveContent discovery : discoveries) {
            map.merge(parseEntityType(discovery.entityType()).name(), 1, Integer::sum);
        }
        return map;
    }

    private Map<String, Integer> buildDetectedEntityCounts(List<DetectedEntity> entities) {
        Map<String, Integer> map = new java.util.TreeMap<>();
        for (DetectedEntity entity : entities) {
            map.merge(parseEntityType(entity.entityType()).name(), 1, Integer::sum);
        }
        return map;
    }

    private void handleFailure(DetectSensitiveDataCommand command, CorrelationId correlationId, Throwable failure) {
        String safeReason = TechnicalFailureClassifier.safeAuditDetail(
                failure, FailureReasonSanitizer.sensitiveDataDetectionFailed());
        try {
            documentRepository.findByIdAndTenantId(command.documentId(), command.tenantId())
                    .ifPresent(doc -> {
                        doc.fail();
                        documentRepository.save(doc);
                    });
        } catch (Exception ignored) {
            // best effort
        }

        try {
            eventPublisher.publish(DocumentDetectionFailedEvent.of(
                    command.documentId(), command.packageId(), command.tenantId(),
                    safeReason, correlationId));
        } catch (Exception ignored) {
            // best effort
        }

        try {
            auditService.recordFailure(
                    command.tenantId(), SYSTEM_ACTOR,
                    AuditAction.DOCUMENT_PROCESSING_FAILED,
                    "Document", command.documentId().toString(), correlationId, safeReason);
        } catch (Exception ignored) {
            // best effort
        }
    }
}
