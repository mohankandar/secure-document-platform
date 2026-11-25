package com.securedoc.platform.application.detection.service;

import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.application.detection.command.DetectSensitiveDataCommand;
import com.securedoc.platform.application.detection.result.DetectSensitiveDataResult;
import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.pipeline.PageBatch;
import com.securedoc.platform.application.pipeline.PageBatchCommand;
import com.securedoc.platform.application.pipeline.PageBatchFiles;
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
import com.securedoc.platform.application.port.out.PageRouteRepository;
import com.securedoc.platform.application.vision.VisionPageReader;
import com.securedoc.platform.domain.finding.AiModel;
import com.securedoc.platform.domain.finding.DetectionPath;
import com.securedoc.platform.domain.finding.DetectorSource;
import com.securedoc.platform.domain.finding.FindingOrigin;
import com.securedoc.platform.domain.vision.PageRoute;
import com.securedoc.platform.domain.vision.VisionPageResult;
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
    /**
     * Task 0064: an audit detail is cut at 2000 characters (and the compliance appendix at 900), so routed pages are named only while there is room for a
     * whole one; every page's route is in document_page_routes.
     */
    private static final int MAX_DETAIL_FOR_ROUTED = 840;

    private final DocumentRepository documentRepository;
    private final PackageRepository packageRepository;
    private final ArtifactRepository artifactRepository;
    private final StoragePort storagePort;
    private final SensitiveContentAnalysisPort semanticAnalysisPort;
    private final SensitiveDataDetectionPort deterministicDetectionPort;
    private final SensitiveDataValidationPort deterministicValidationPort;
    private final TenantRedactionPolicyRepository tenantRedactionPolicyRepository;
    private final FindingEvidenceConfidencePolicy evidenceConfidencePolicy = new FindingEvidenceConfidencePolicy();
    private final SensitiveCandidatePlausibilityPolicy candidatePlausibilityPolicy = new SensitiveCandidatePlausibilityPolicy();
    private final OcrSensitiveFieldSafetyPolicy ocrSensitiveFieldSafetyPolicy = new OcrSensitiveFieldSafetyPolicy();
    private final TextExtractionPort textExtractionPort;
    private final FindingRepository findingRepository;
    private final DomainEventPublisher eventPublisher;
    private final AuditService auditService;
    /** Task 0049: null where page batches are not wired (they are refused there). */
    private final PageBatchFiles batchFiles;
    /** Task 0064: null where vision is not wired (then no page is routed). */
    private final Vision vision;

    /** Task 0064: the pages' routes and the reader of routed pages. */
    public record Vision(PageRouteRepository routes, VisionPageReader reader) {
        public Vision {
            Objects.requireNonNull(routes);
            Objects.requireNonNull(reader);
        }
    }

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
        this(documentRepository, packageRepository, artifactRepository, storagePort, semanticAnalysisPort,
                deterministicDetectionPort, deterministicValidationPort, tenantRedactionPolicyRepository,
                textExtractionPort, findingRepository, eventPublisher, auditService, null);
    }

    /** Task 0049: with the checksums of the page batch results. */
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
                                         AuditService auditService,
                                         com.securedoc.platform.application.port.out.ChecksumPort checksumPort) {
        this(documentRepository, packageRepository, artifactRepository, storagePort, semanticAnalysisPort,
                deterministicDetectionPort, deterministicValidationPort, tenantRedactionPolicyRepository,
                textExtractionPort, findingRepository, eventPublisher, auditService, checksumPort, null);
    }

    /** Task 0064: with page routing and the vision model. */
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
                                         AuditService auditService,
                                         com.securedoc.platform.application.port.out.ChecksumPort checksumPort,
                                         Vision vision) {
        this.vision = vision;
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
        this.batchFiles = checksumPort == null ? null
                : new PageBatchFiles(artifactRepository, storagePort, checksumPort);
    }

    /** Task 0065: null where it is not wired (then no document has a context). */
    private DocumentContext context;

    public SensitiveDataDetectionService withContext(DocumentContext documentContext) {
        this.context = Objects.requireNonNull(documentContext);
        return this;
    }

    @Override
    public void prepareBatch(DetectSensitiveDataCommand command) {
        if (context == null) return;
        TenantRedactionPolicy policy = policyOf(command.tenantId());
        if (DocumentContext.wanted(policy)) context.prepare(loadDetecting(command), policy);
    }

    @Override
    public DetectSensitiveDataResult execute(DetectSensitiveDataCommand command) {
        Objects.requireNonNull(command, "Command must not be null");
        CorrelationId correlationId = command.correlationId();

        try {
            return doDetection(command, correlationId, Map.of());
        } catch (ValidationException | NotFoundException e) {
            throw e;
        } catch (Exception e) {
            handleFailure(command, correlationId, e);
            throw e;
        }
    }

    /**
     * Task 0049: the same search as {@link #execute}, for one batch's pages. Nothing is decided here: the document's
     * state, findings, events and audit are written once, when the batches are gathered.
     */
    @Override
    public void detectBatch(PageBatchCommand command) {
        Objects.requireNonNull(command, "Command must not be null");
        DetectSensitiveDataCommand whole = DetectSensitiveDataCommand.of(command.documentId(), command.packageId(),
                command.tenantId(), command.correlationId());
        try {
            Document document = loadDetecting(whole);
            TenantRedactionPolicy policy = policyOf(command.tenantId());
            Map<Integer, PageText> pages = extractPagePositions(document, command.tenantId(), command.batch().pages());
            PageFindings found = findOnPages(whole, document, policy, pages,
                    context == null ? KnownNames.NONE : context.ofBatch(document, policy));
            batchFiles().save(com.securedoc.platform.domain.artifact.ArtifactType.FINDINGS_BATCH, command, out ->
                    PageBatchFindings.write(out, new PageBatchFindings.Result(stamp(document, policy),
                            found.unlocatedSemantic(),
                            found.findings().stream().map(PageBatchFindings.Candidate::of).toList(),
                            found.models())));
        } catch (ValidationException | NotFoundException e) {
            throw e;
        } catch (RuntimeException e) {
            handleFailure(whole, command.correlationId(), e);
            throw e;
        }
    }

    @Override
    public DetectSensitiveDataResult completeFromBatches(DetectSensitiveDataCommand command, List<PageBatch> batches) {
        return completeFromBatches(command, batches, List.of());
    }

    @Override
    public List<Integer> pagesForVision(DetectSensitiveDataCommand command) {
        // Switched off: nothing to wait for; the completion sends the routed pages to a person.
        if (vision == null || !policyOf(command.tenantId()).vision().enabled()) return List.of();
        return vision.routes().findByDocument(command.tenantId(), command.documentId()).stream()
                .filter(PageRoute::vision).map(PageRoute::page).toList();
    }

    @Override
    public VisionPageResult readVisionPage(DetectSensitiveDataCommand command, int page) {
        Document document = loadDetecting(command);
        return vision().reader().read(document, policyOf(command.tenantId()).vision(), page);
    }

    @Override
    public void saveVisionPage(PageBatchCommand command, VisionPageResult result) {
        vision();
        if (command.batch().from() != result.page() || command.batch().to() != result.page()) {
            throw new IllegalArgumentException("A vision job has one page");
        }
        batchFiles().save(ArtifactType.VISION_PAGE, command, out -> VisionFindings.write(out, result));
    }

    private Vision vision() {
        if (vision == null) throw new UnsupportedOperationException("Vision is not supported");
        return vision;
    }

    @Override
    public DetectSensitiveDataResult completeFromBatches(DetectSensitiveDataCommand command, List<PageBatch> batches,
                                                         List<PageBatch> visionPages) {
        Objects.requireNonNull(command, "Command must not be null");
        CorrelationId correlationId = command.correlationId();
        try {
            long startedNanos = System.nanoTime();
            Document document = loadDetecting(command);
            TenantRedactionPolicy policy = policyOf(command.tenantId());
            requireEveryPage(batches, document.pageCount());
            List<PageBatchFindings.Result> results = batchFiles().load(
                    com.securedoc.platform.domain.artifact.ArtifactType.FINDINGS_BATCH, command.documentId(),
                    command.tenantId(), batches, PageBatchFindings::read);
            Map<Integer, VisionPageResult> pagesRead = new HashMap<>();
            if (!visionPages.isEmpty()) {
                for (VisionPageResult page : batchFiles().load(ArtifactType.VISION_PAGE, command.documentId(),
                        command.tenantId(), visionPages, VisionFindings::read)) {
                    pagesRead.put(page.page(), page);
                }
            }
            DetectSensitiveDataResult result;
            String stamp = stamp(document, policy);
            if (results.stream().anyMatch(r -> !r.policyStamp().equals(stamp))) {
                // The entity's policy changed between the batches: one policy must decide for the whole document.
                log.info("DETECTION_BATCHES_SEARCHED_AGAIN document={} reasonCode=POLICY_CHANGED",
                        command.documentId());
                result = doDetection(command, correlationId, pagesRead);
            } else {
                int unlocated = results.stream().mapToInt(PageBatchFindings.Result::unlocatedSemantic).max().orElse(0);
                List<AiModel> textModels = results.stream().flatMap(r -> r.models().stream()).distinct().toList();
                AllFindings all = withVision(document, policy, PageBatchFindings.merge(batches, results), pagesRead,
                        textModels);
                result = complete(command, document, policy, all, unlocated, startedNanos, correlationId);
            }
            if (context != null) context.discard(document);
            if (!visionPages.isEmpty()) {
                batchFiles().discard(ArtifactType.VISION_PAGE, command.documentId(), command.tenantId());
            }
            batchFiles().discard(com.securedoc.platform.domain.artifact.ArtifactType.FINDINGS_BATCH,
                    command.documentId(), command.tenantId());
            return result;
        } catch (ValidationException | NotFoundException e) {
            throw e;
        } catch (RuntimeException e) {
            handleFailure(command, correlationId, e);
            throw e;
        }
    }

    private static void requireEveryPage(List<PageBatch> batches, int pageCount) {
        for (int page = 1; page <= pageCount; page++) {
            int wanted = page;
            if (batches.stream().noneMatch(batch -> batch.covers(wanted))) {
                throw new IllegalStateException("The page batches do not cover every page");
            }
        }
    }

    private PageBatchFiles batchFiles() {
        if (batchFiles == null) throw new UnsupportedOperationException("Page batches are not supported");
        return batchFiles;
    }

    /** Task 0065: with the setting and the case's instructions, when the setting is on. */
    private String stamp(Document document, TenantRedactionPolicy policy) {
        return policyStamp(policy) + (context == null ? "" : context.stamp(document, policy));
    }

    /** What a batch searched for: a policy change between batches is noticed. Names of kinds only. */
    private static String policyStamp(TenantRedactionPolicy policy) {
        return policy.enabledEntityTypes().stream().map(Enum::name).sorted()
                .collect(java.util.stream.Collectors.joining(",")) + ";maskSsnLastFour=" + policy.maskSsnLastFour();
    }

    private TenantRedactionPolicy policyOf(com.securedoc.platform.domain.identity.TenantId tenantId) {
        return tenantRedactionPolicyRepository.findByTenantId(tenantId)
                .orElseGet(() -> TenantRedactionPolicy.defaults(tenantId));
    }

    private DetectSensitiveDataResult doDetection(DetectSensitiveDataCommand command, CorrelationId correlationId,
                                                  Map<Integer, VisionPageResult> pagesRead) {
        long startedNanos = System.nanoTime();
        Document document = loadDetecting(command);
        TenantRedactionPolicy tenantPolicy = policyOf(command.tenantId());

        // Trusted positional text is the common normalization layer for both semantic discovery
        // and deterministic recognition. Unit 17 will feed OCR text/coordinates into the same shape.
        Map<Integer, PageText> pagesByNumber = extractPagePositions(document, command.tenantId(), null);
        PageFindings found = findOnPages(command, document, tenantPolicy, pagesByNumber,
                context == null ? KnownNames.NONE : context.of(document, tenantPolicy));
        AllFindings all = vision == null
                ? new AllFindings(found.findings(), List.of(), Map.of(), found.models())
                : withVision(document, tenantPolicy,
                        found.findings().stream().map(PageBatchFindings.Candidate::of).toList(), pagesRead,
                        found.models());
        return complete(command, document, tenantPolicy, all, found.unlocatedSemantic(), startedNanos, correlationId);
    }

    /** Findings before the confidence policy, how many semantic findings have no place, and the AI models asked. */
    private record PageFindings(List<Finding> findings, int unlocatedSemantic, List<AiModel> models) { }

    /**
     * Every path's findings, before the confidence policy.
     *
     * @param notRead the routed pages the vision model did not read, with why (a code or an internal code)
     */
    private record AllFindings(List<Finding> findings, List<PageRoute> routes, Map<Integer, String> notRead,
                               List<AiModel> models) { }

    /**
     * Task 0064: the text path's findings with the signature detector's and the vision model's. Every routed page is
     * read here if no job read it, or goes to a person: a page never passes unread.
     */
    private AllFindings withVision(Document document, TenantRedactionPolicy policy,
                                   List<PageBatchFindings.Candidate> text, Map<Integer, VisionPageResult> pagesRead,
                                   List<AiModel> textModels) {
        List<PageRoute> routes = vision == null ? List.of() : routesOf(document, policy);
        List<PageBatchFindings.Candidate> others = new ArrayList<>();
        Map<Integer, String> notRead = new java.util.TreeMap<>();
        Set<AiModel> models = new java.util.LinkedHashSet<>(textModels);
        for (PageRoute route : routes) {
            if (route.path() == PageRoute.Path.FAST_SIGNATURE) {
                others.addAll(VisionFindings.signatures(route, policy.enabledEntityTypes()));
            }
            if (!route.vision()) continue;
            VisionPageResult result = pagesRead.get(route.page());
            if (result == null) result = vision.reader().read(document, policy.vision(), route.page());
            if (result.read()) {
                models.add(result.model());
            } else {
                notRead.put(route.page(), result.code() == null ? result.reason() : result.code());
                others.addAll(VisionFindings.signatures(route, policy.enabledEntityTypes()));
            }
            others.addAll(VisionFindings.of(result, policy));
        }
        List<Finding> findings = new ArrayList<>();
        for (PageBatchFindings.Candidate candidate : VisionFindings.merge(text, others)) {
            findings.add(Finding.create(document.tenantId(), document.id(), candidate.type(), candidate.location(),
                    Confidence.of(candidate.confidence()), candidate.source(), candidate.origin()));
        }
        return new AllFindings(findings, routes, notRead, List.copyOf(models));
    }

    /** The routes stored when the text was read. A page without one (read before task 0064) is routed now. */
    private List<PageRoute> routesOf(Document document, TenantRedactionPolicy policy) {
        Map<Integer, PageRoute> byPage = new java.util.TreeMap<>();
        for (PageRoute route : vision.routes().findByDocument(document.tenantId(), document.id())) {
            byPage.put(route.page(), route);
        }
        Set<Integer> missing = new java.util.TreeSet<>();
        for (int page = 1; page <= document.pageCount(); page++) if (!byPage.containsKey(page)) missing.add(page);
        if (!missing.isEmpty()) {
            List<PageRoute> added = new ArrayList<>();
            try (InputStream stream = storagePort.retrieve(original(document, document.tenantId()).storageReference())) {
                for (PageRoute route : textExtractionPort.extractRouted(stream, "application/pdf", missing,
                        policy.vision().ocrThreshold()).routes()) {
                    if (missing.remove(route.page())) added.add(route);
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Failed to read the original document", e);
            }
            // A page the checks say nothing about is a hard page (fail closed).
            for (int page : missing) {
                added.add(new PageRoute(page, PageRoute.Path.VISION, Set.of(PageRoute.Reason.CHECK_FAILED), null,
                        List.of()));
            }
            vision.routes().save(document.tenantId(), document.id(), added);
            added.forEach(route -> byPage.put(route.page(), route));
        }
        // Only this document's pages count.
        return byPage.values().stream().filter(route -> route.page() <= document.pageCount()).toList();
    }

    private Document loadDetecting(DetectSensitiveDataCommand command) {
        Document document = documentRepository
                .findByIdAndTenantId(command.documentId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("Document", command.documentId().toString()));

        if (!document.packageId().equals(command.packageId())) {
            throw new ValidationException(LvErrorCode.PRC_009, "Document does not belong to the specified package");
        }

        DocumentPackage parentPackage = packageRepository
                .findByIdAndTenantId(command.packageId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("DocumentPackage", command.packageId().toString()));
        if (parentPackage.status() != PackageStatus.PROCESSING) {
            throw new ValidationException(LvErrorCode.PRC_009, "Package is not in PROCESSING status");
        }
        if (document.status() != DocumentStatus.DETECTING) {
            throw new ValidationException(LvErrorCode.PRC_009, "Document is not in DETECTING status");
        }

        return document;
    }

    private PageFindings findOnPages(DetectSensitiveDataCommand command, Document document,
                                     TenantRedactionPolicy tenantPolicy, Map<Integer, PageText> pagesByNumber,
                                     KnownNames known) {
        Set<EntityType> enabledTypes = tenantPolicy.enabledEntityTypes();
        List<PageInput> deterministicInputs = buildDeterministicInputs(pagesByNumber);
        List<SensitiveContentAnalysisPort.PageContent> semanticInputs = buildSemanticInputs(pagesByNumber);

        // AI-first: semantic analysis always runs independently for every text-bearing document.
        // Truly blank pages were already safely inspected by native extraction/OCR and do not need
        // a meaningless model call.
        boolean hasTextBearingPage = pagesByNumber.values().stream().anyMatch(page -> !page.text().isBlank());
        // Task 0043: the semantic analyzer is asked only for the kinds it knows. The pattern kinds
        // (routing, account, ITIN, EIN, driver's license, passport, CVV, PIN) come from the deterministic detectors only.
        Set<EntityType> semanticTypes = java.util.EnumSet.noneOf(EntityType.class);
        semanticTypes.addAll(enabledTypes);
        semanticTypes.retainAll(com.securedoc.platform.domain.business.RedactionCatalog.askedOfAi());
        SensitiveContentAnalysisPort.AnalysisPolicy analysisPolicy =
                new SensitiveContentAnalysisPort.AnalysisPolicy(semanticTypes, tenantPolicy.maskSsnLastFour());
        SensitiveContentAnalysisPort.AnalysisOutput semanticOutput = hasTextBearingPage && !semanticTypes.isEmpty()
                ? semanticAnalysisPort.analyze(new SensitiveContentAnalysisPort.AnalysisRequest(
                        command.tenantId(), semanticInputs, analysisPolicy))
                : new SensitiveContentAnalysisPort.AnalysisOutput(List.of());
        DetectionOutput deterministicOutput = hasTextBearingPage
                ? deterministicDetectionPort.detect(deterministicInputs)
                : new DetectionOutput(List.of());

        SemanticResolution semanticResolution = resolveSemanticDiscoveries(semanticOutput.findings(), pagesByNumber, enabledTypes, tenantPolicy.maskSsnLastFour());
        List<DetectedEntity> mergedEntities = mergeEvidence(semanticResolution.located(), deterministicOutput.entities(), enabledTypes);

        // OCR fail-safe: a strong structured field label must not silently disappear between OCR
        // and policy evaluation. Exact OCR credit-score values become normal located findings;
        // unresolved non-empty sensitive fields become review-only signals that block export.
        OcrSensitiveFieldSafetyPolicy.Evaluation ocrSafety = ocrSensitiveFieldSafetyPolicy.evaluate(
                pagesByNumber, mergedEntities, enabledTypes);
        mergedEntities = mergeEvidence(mergedEntities, ocrSafety.locatedEntities(), enabledTypes);
        mergedEntities = new ArrayList<>(itinWinsOverSsn(mergedEntities));

        Set<String> aiNames = semanticOutput.findings().stream().map(DiscoveredSensitiveContent::analyzerName)
                .collect(java.util.stream.Collectors.toCollection(java.util.HashSet::new));
        List<AiModel> models = new ArrayList<>(semanticOutput.models());
        if (!known.forms().isEmpty()) {
            SensitiveContentAnalysisPort.AnalysisOutput answered = knownNames(command, pagesByNumber, known, mergedEntities);
            answered.findings().forEach(f -> aiNames.add(f.analyzerName()));
            models.addAll(known.models());
            models.addAll(answered.models());
        }
        List<Finding> findings = createFindings(new DetectionOutput(mergedEntities), document, pagesByNumber,
                enabledTypes, aiNames, models);
        findings.addAll(createUnlocatedSemanticFindings(semanticResolution.unlocated(), document, models));
        findings.addAll(createOcrSafetyReviewFindings(ocrSafety.reviewSignals(), document));

        log.info("FINDING_EVIDENCE_SUMMARY document={} semanticRaw={} semanticLocated={} semanticUnlocated={} deterministic={} ocrGuardLocated={} ocrGuardReview={} ocrGuardStrongLabels={} ocrGuardSpatialScoreCandidates={} merged={} semanticByType={} deterministicByType={} semanticLocatedByPageType={} deterministicByPageType={}",
                command.documentId(), semanticOutput.findings().size(), semanticResolution.located().size(),
                semanticResolution.unlocated().size(), deterministicOutput.entities().size(),
                ocrSafety.locatedEntities().size(), ocrSafety.reviewSignals().size(),
                ocrSafety.strongLabelCount(), ocrSafety.spatialScoreCandidateCount(), mergedEntities.size(),
                buildDiscoveryCounts(semanticOutput.findings()), buildDetectedEntityCounts(deterministicOutput.entities()),
                buildDetectedEntityPageTypeCounts(semanticResolution.located(), enabledTypes),
                buildDetectedEntityPageTypeCounts(deterministicOutput.entities(), enabledTypes));
        return new PageFindings(findings, semanticResolution.unlocated().size(), models);
    }

    /**
     * Task 0065: the places where a name the document is known to use stands and the names search found none: the model is
     * asked what it is in each sentence, and a person is added to {@code entities}. Each place is decided by itself.
     */
    private SensitiveContentAnalysisPort.AnalysisOutput knownNames(DetectSensitiveDataCommand command,
                                                                   Map<Integer, PageText> pages, KnownNames known,
                                                                   List<DetectedEntity> entities) {
        List<Integer> pageOf = new ArrayList<>();
        List<KnownNames.Place> places = new ArrayList<>();
        pages.forEach((number, page) -> {
            List<int[]> taken = entities.stream().filter(e -> e.pageNumber() == number
                    && parseEntityType(e.entityType()) == EntityType.PERSON_NAME)
                    .map(e -> new int[]{e.startOffset(), e.endOffset()}).toList();
            for (KnownNames.Place place : known.places(page.text(), taken)) {
                pageOf.add(number);
                places.add(place);
            }
        });
        if (places.isEmpty()) return new SensitiveContentAnalysisPort.AnalysisOutput(List.of());
        List<SensitiveContentAnalysisPort.PageContent> sentences = new ArrayList<>();
        for (int i = 0; i < places.size(); i++) {
            sentences.add(new SensitiveContentAnalysisPort.PageContent(i + 1, places.get(i).sentence()));
        }
        SensitiveContentAnalysisPort.AnalysisOutput output = semanticAnalysisPort.analyze(
                new SensitiveContentAnalysisPort.AnalysisRequest(command.tenantId(), sentences,
                        SensitiveContentAnalysisPort.AnalysisPolicy.of(
                                SensitiveContentAnalysisPort.AnalysisPolicy.Ask.KNOWN_NAMES)));
        int persons = 0;
        for (DiscoveredSensitiveContent answer : output.findings()) {
            if (answer.pageNumber() > places.size()) continue;
            KnownNames.Place place = places.get(answer.pageNumber() - 1);
            entities.add(new DetectedEntity(EntityType.PERSON_NAME.name(), pageOf.get(answer.pageNumber() - 1),
                    place.start(), place.end(), answer.confidence(), answer.analyzerName(), answer.analyzerVersion()));
            persons++;
        }
        // Counts only.
        log.info("KNOWN_NAMES_ASKED document={} forms={} places={} persons={}", command.documentId(), known.forms().size(),
                places.size(), persons);
        return output;
    }

    /** Policy, findings, state, events and audit of the document (whole document or gathered batches). */
    private DetectSensitiveDataResult complete(DetectSensitiveDataCommand command, Document document,
                                               TenantRedactionPolicy tenantPolicy, AllFindings all,
                                               int unlocatedSemantic, long startedNanos,
                                               CorrelationId correlationId) {
        List<Finding> findings = all.findings();
        Set<EntityType> enabledTypes = tenantPolicy.enabledEntityTypes();
        // Per-finding policy. Only findings that individually meet confidence AND have a trusted
        // location are auto-confirmed. Low-confidence/unlocated findings remain DETECTED for review.
        for (Finding finding : findings) {
            // Task 0062: the style of this moment goes with the finding, so a later change reaches new documents only.
            // Task 0064: what the page image showed has no reliable place in the text: it is always removed in full.
            finding.applyStyle(finding.origin().paths().contains(DetectionPath.VISION)
                    ? com.securedoc.platform.domain.business.RedactionStyle.REMOVE_FULLY
                    : tenantPolicy.styleOf(finding.entityType()));
            boolean autoConfirmAllowed = tenantPolicy.reviewMode() == ReviewMode.UNCERTAIN_ONLY;
            // Task 0072: a name is always a person's decision.
            if (autoConfirmAllowed
                    && !com.securedoc.platform.domain.business.RedactionCatalog.alwaysReviewed().contains(finding.entityType())
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
        Map<String, Integer> finalPageTypeCounts = buildFindingPageTypeCounts(findings);
        Map<String, Integer> multiFindingPageTypes = finalPageTypeCounts.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue,
                        (left, right) -> left, java.util.TreeMap::new));
        long autoConfirmedCount = findings.stream().filter(f -> f.status() == FindingStatus.CONFIRMED).count();
        log.info("DOCUMENT_DETECTION_COMPLETED document={} package={} findings={} byType={} pagesByType={} pageTypeCounts={} multiFindingPageTypes={} autoConfirmed={} reviewRequired={} decision={} enabledTypes={} maskSsnLastFour={} durationMs={}",
                command.documentId(), command.packageId(), findings.size(), findingsByType, findingPagesByType,
                finalPageTypeCounts, multiFindingPageTypes, autoConfirmedCount, requiresReview, overallDecision,
                enabledTypes, tenantPolicy.maskSsnLastFour(), elapsedMillis(startedNanos));

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
                "Document", command.documentId().toString(), command.packageId(), correlationId,
                withPagePaths("Detection completed: findings=" + findings.size()
                        + ", autoConfirmed=" + findings.stream().filter(f -> f.status() == FindingStatus.CONFIRMED).count()
                        + ", requiresReview=" + requiresReview
                        + ", threshold=" + tenantPolicy.autoRedactThreshold()
                        + ", reviewMode=" + tenantPolicy.reviewMode()
                        + ", enabledTypes=" + enabledTypes.size()
                        + ", unlocatedSemantic=" + unlocatedSemantic
                        + ", decision=" + overallDecision
                        + ", types=" + findingsByType, all));

        return DetectSensitiveDataResult.of(
                command.documentId(), document.status(), findings.size(),
                findingsByType, requiresRedaction, overallDecision);
    }

    /**
     * Task 0064: how the pages were read and the AI models that took part (page numbers, reason names, OCR confidence,
     * codes, model names and digests; never a value). Nothing when neither is known, so older details stay as they were.
     */
    private static String withPagePaths(String base, AllFindings all) {
        StringBuilder detail = new StringBuilder(base);
        List<PageRoute> vision = all.routes().stream().filter(PageRoute::vision).toList();
        if (!all.routes().isEmpty()) {
            detail.append(", textPages=").append(all.routes().size() - vision.size())
                    .append(", signaturePages=").append(all.routes().stream()
                            .filter(r -> r.path() == PageRoute.Path.FAST_SIGNATURE).count())
                    .append(", visionPages=").append(vision.size() - all.notRead().size())
                    .append(", visionPagesToPerson=").append(all.notRead().size());
        }
        if (!all.models().isEmpty()) {
            detail.append(", aiModels=[").append(FindingOrigin.encodeModels(all.models())).append(']');
        }
        if (!vision.isEmpty()) {
            detail.append(", routed=[");
            for (int i = 0; i < vision.size(); i++) {
                PageRoute route = vision.get(i);
                if (detail.length() > MAX_DETAIL_FOR_ROUTED) {
                    detail.append(" +").append(vision.size() - i);
                    break;
                }
                if (i > 0) detail.append(' ');
                detail.append('p').append(route.page()).append(':')
                        .append(route.reasons().stream().map(Enum::name)
                                .collect(java.util.stream.Collectors.joining("+")));
                if (route.ocrConfidence() != null) {
                    detail.append("@ocr").append(String.format(java.util.Locale.ROOT, "%.2f", route.ocrConfidence()));
                }
                String why = all.notRead().get(route.page());
                if (why != null) detail.append("->PERSON(").append(why).append(')');
            }
            detail.append(']');
        }
        return detail.toString();
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

            int[] offsets = resolveOffsets(page.text(), matchedText, discovery.contextText());
            String trustedContext = trustedContext(page.text(), offsets, discovery.contextText());
            ValidationEvidence validation = deterministicValidationPort.validate(new Candidate(
                    discovery.entityType(), matchedText, trustedContext));
            SensitiveCandidatePlausibilityPolicy.Evaluation plausibility = candidatePlausibilityPolicy.evaluate(
                    type, matchedText, trustedContext, maskSsnLastFour, validation.outcome());
            if (!plausibility.plausible()) {
                // Do not persist impossible/contradictory classifications as low-confidence review
                // clutter. Log only type/page/reason; never the candidate value or surrounding text.
                log.info("AI_FINDING_REJECTED type={} page={} reason={} validation={}",
                        type, discovery.pageNumber(), plausibility.reasonCode(), validation.outcome());
                continue;
            }

            double effectiveConfidence = evidenceConfidencePolicy.effectiveSemanticConfidence(
                    discovery.confidence(), validation.outcome());
            String analyzerVersion = discovery.analyzerVersion()
                    + ";validation=" + validation.outcome() + ";plausibility=PASS";

            if (type == EntityType.PERSON_NAME) {
                // Task 0072: the same written name on the page is the same person, so each place is found.
                List<int[]> places = wholeWords(page.text(), matchedText);
                if (places.isEmpty()) {
                    unlocated.add(new UnlocatedSemanticFinding(type, discovery.pageNumber(), effectiveConfidence,
                            discovery.analyzerName(), analyzerVersion));
                }
                for (int[] place : places) {
                    located.add(new DetectedEntity(discovery.entityType(), discovery.pageNumber(), place[0], place[1],
                            effectiveConfidence, discovery.analyzerName(), analyzerVersion));
                }
                continue;
            }

            if (offsets == null) {
                // A structurally/contextually plausible candidate may still be ambiguous to locate
                // when the exact value occurs more than once. Preserve that uncertainty for review,
                // but only after the plausibility gate above has rejected obvious garbage.
                unlocated.add(new UnlocatedSemanticFinding(
                        type, discovery.pageNumber(), effectiveConfidence,
                        discovery.analyzerName(), analyzerVersion));
                continue;
            }

            located.add(new DetectedEntity(
                    discovery.entityType(), discovery.pageNumber(), offsets[0], offsets[1],
                    effectiveConfidence, discovery.analyzerName(), analyzerVersion));
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
                                                           Document document, List<AiModel> models) {
        List<Finding> findings = new ArrayList<>(unlocated.size());
        for (UnlocatedSemanticFinding candidate : unlocated) {
            findings.add(Finding.create(
                    document.tenantId(), document.id(), candidate.entityType(),
                    FindingLocation.unlocated(candidate.pageNumber()),
                    Confidence.of(candidate.confidence()),
                    new DetectorSource(candidate.detectorName(), candidate.detectorVersion()),
                    FindingOrigin.of(DetectionPath.TEXT, models)));
        }
        return findings;
    }

    private List<Finding> createOcrSafetyReviewFindings(
            List<OcrSensitiveFieldSafetyPolicy.ReviewSignal> reviewSignals,
            Document document) {
        List<Finding> findings = new ArrayList<>(reviewSignals.size());
        for (OcrSensitiveFieldSafetyPolicy.ReviewSignal signal : reviewSignals) {
            findings.add(Finding.create(
                    document.tenantId(), document.id(), signal.entityType(),
                    FindingLocation.unlocated(signal.pageNumber()),
                    Confidence.of(signal.confidence()),
                    new DetectorSource("ocr-sensitive-field-guard", "1.4.0;unresolved-field"),
                    FindingOrigin.of(DetectionPath.TEXT, List.of())));
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

    private String trustedContext(String pageText, int[] offsets, String groundedModelContext) {
        if (offsets != null) {
            int radius = 220;
            int start = Math.max(0, offsets[0] - radius);
            int end = Math.min(pageText.length(), offsets[1] + radius);
            String window = pageText.substring(start, end);
            if (groundedModelContext != null && !groundedModelContext.isBlank()
                    && !window.contains(groundedModelContext)) {
                return window + "\n" + groundedModelContext;
            }
            return window;
        }
        // SensitiveContentResponseParser keeps contextText only when it is an exact quote from the
        // declared page and contains matchedText, so it is safe as trusted context for an unlocated
        // repeated value. An empty string intentionally makes context-dependent types fail closed.
        return groundedModelContext == null ? "" : groundedModelContext;
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

    /** Every place of the words, in capitals or not; inside a longer word it is another word. */
    private static List<int[]> wholeWords(String text, String words) {
        List<int[]> places = new ArrayList<>();
        var matcher = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(words),
                java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE).matcher(text);
        while (matcher.find()) {
            int at = matcher.start();
            int end = matcher.end();
            boolean before = at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1));
            boolean after = end == text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (before && after) places.add(new int[]{at, end});
        }
        return places;
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

            String sourceName = independentAgreement
                    ? existing.detectorName() + "+" + candidate.detectorName()
                    : existing.detectorName();

            merged.set(existingIndex, new DetectedEntity(
                    existing.entityType(), existing.pageNumber(),
                    Math.min(existing.startOffset(), candidate.startOffset()),
                    Math.max(existing.endOffset(), candidate.endOffset()),
                    confidence, sourceName, "merged-v1"));
        }
        return merged;
    }

    /**
     * Task 0047, item 3 (owner, 0044 answers 5): a value found both as an SSN and as an ITIN ("SSN or ITIN:
     * 9NN-NN-NNNN") is one finding, the ITIN: an SSN never starts with 9, and an ITIN finding is only made for a
     * 9NN value in the ITIN groups. The ITIN keeps the union of both spans, so the box never covers less than either.
     * An ITIN finding exists only when the entity has ITIN on, so with ITIN off the SSN stays as it was.
     */
    private List<DetectedEntity> itinWinsOverSsn(List<DetectedEntity> entities) {
        List<DetectedEntity> itins = new ArrayList<>();
        for (DetectedEntity e : entities) {
            if (parseEntityType(e.entityType()) == EntityType.ITIN) itins.add(e);
        }
        if (itins.isEmpty()) return entities;
        java.util.Map<DetectedEntity, int[]> itinSpans = new java.util.IdentityHashMap<>();
        java.util.Set<DetectedEntity> foldedSsns = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (DetectedEntity ssn : entities) {
            if (parseEntityType(ssn.entityType()) != EntityType.SSN) continue;
            for (DetectedEntity itin : itins) {
                if (itin.pageNumber() != ssn.pageNumber()
                        || itin.startOffset() >= ssn.endOffset() || ssn.startOffset() >= itin.endOffset()) continue;
                int[] span = itinSpans.computeIfAbsent(itin, i -> new int[]{i.startOffset(), i.endOffset()});
                span[0] = Math.min(span[0], ssn.startOffset());
                span[1] = Math.max(span[1], ssn.endOffset());
                foldedSsns.add(ssn);
                break;
            }
        }
        if (foldedSsns.isEmpty()) return entities;
        List<DetectedEntity> result = new ArrayList<>();
        for (DetectedEntity e : entities) {
            if (foldedSsns.contains(e)) continue;
            int[] span = itinSpans.get(e);
            result.add(span == null ? e : new DetectedEntity(e.entityType(), e.pageNumber(), span[0], span[1],
                    e.confidence(), e.detectorName(), e.detectorVersion()));
        }
        return result;
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
                                         Set<EntityType> enabledTypes, Set<String> aiNames,
                                         List<AiModel> models) {
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
                    FindingOrigin.of(DetectionPath.TEXT,
                            aiNames.stream().anyMatch(entity.detectorName()::contains) ? models : List.of())));
        }
        return findings;
    }

    private Map<Integer, PageText> extractPagePositions(Document document,
                                                        com.securedoc.platform.domain.identity.TenantId tenantId,
                                                        Set<Integer> onlyPages) {
        Artifact originalArtifact = original(document, tenantId);

        Map<Integer, PageText> byNumber = new LinkedHashMap<>();
        try (InputStream stream = storagePort.retrieve(originalArtifact.storageReference())) {
            // Task 0064: a page the text reader cannot read is routed to vision and fails nothing here.
            TextExtractionOutput output = vision != null
                    ? textExtractionPort.extractReadable(stream, "application/pdf", onlyPages)
                    : onlyPages == null
                    ? textExtractionPort.extract(stream, "application/pdf")
                    : textExtractionPort.extractPages(stream, "application/pdf", onlyPages);
            output.pages().stream()
                    .sorted(Comparator.comparingInt(PageText::pageNumber))
                    .forEach(page -> byNumber.put(page.pageNumber(), page));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to extract positional page data", e);
        }
        return byNumber;
    }

    private Artifact original(Document document, com.securedoc.platform.domain.identity.TenantId tenantId) {
        return artifactRepository
                .findByDocumentIdAndTenantId(document.id(), tenantId)
                .stream()
                .filter(a -> a.artifactType() == ArtifactType.ORIGINAL && a.isAvailable())
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Original artifact for document",
                        document.id().toString()));
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

    private Map<String, Integer> buildDetectedEntityPageTypeCounts(List<DetectedEntity> entities,
                                                                    Set<EntityType> enabledTypes) {
        Map<String, Integer> map = new java.util.TreeMap<>();
        for (DetectedEntity entity : entities) {
            EntityType type = parseEntityType(entity.entityType());
            if (!enabledTypes.contains(type)) {
                continue;
            }
            map.merge("p" + entity.pageNumber() + ":" + type.name(), 1, Integer::sum);
        }
        return map;
    }

    private Map<String, Integer> buildFindingPageTypeCounts(List<Finding> findings) {
        Map<String, Integer> map = new java.util.TreeMap<>();
        for (Finding finding : findings) {
            map.merge("p" + finding.location().pageNumber() + ":" + finding.entityType().name(),
                    1, Integer::sum);
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
                    "Document", command.documentId().toString(), command.packageId(), correlationId, safeReason);
        } catch (Exception ignored) {
            // best effort
        }
    }
}
