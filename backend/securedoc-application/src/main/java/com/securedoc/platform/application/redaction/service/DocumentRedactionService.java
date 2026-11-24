package com.securedoc.platform.application.redaction.service;

import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.resilience.TechnicalFailureClassifier;
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
import com.securedoc.platform.application.port.out.RedactionVerificationPort.RedactedValues;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.application.port.out.TextExtractionPort;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
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
    /** Task 0044; null in the pre-0044 constructor (image-only output, no value comparison). */
    private final TenantRedactionPolicyRepository policyRepository;
    private final TextExtractionPort textExtractionPort;

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
        this(documentRepository, packageRepository, artifactRepository, findingRepository, storagePort,
                pdfRedactionPort, redactionVerificationPort, checksumPort, eventPublisher, auditService, null, null);
    }

    /**
     * Task 0044: with the entity's policy (for "searchable redacted pages") and the product's text extraction (to read
     * the values that were redacted, for the final check). Without them the service behaves as before task 0044:
     * image-only redacted pages and no value comparison.
     */
    public DocumentRedactionService(DocumentRepository documentRepository,
                                     PackageRepository packageRepository,
                                     ArtifactRepository artifactRepository,
                                     FindingRepository findingRepository,
                                     StoragePort storagePort,
                                     PdfRedactionPort pdfRedactionPort,
                                     RedactionVerificationPort redactionVerificationPort,
                                     ChecksumPort checksumPort,
                                     DomainEventPublisher eventPublisher,
                                     AuditService auditService,
                                     TenantRedactionPolicyRepository policyRepository,
                                     TextExtractionPort textExtractionPort) {
        this.policyRepository = policyRepository;
        this.textExtractionPort = textExtractionPort;
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
            throw new ValidationException(LvErrorCode.PRC_009, "Document does not belong to the specified package");
        }

        // 3. Verify parent package is in a valid processing state
        DocumentPackage parentPackage = packageRepository
                .findByIdAndTenantId(command.packageId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("DocumentPackage", command.packageId().toString()));

        if (parentPackage.status() != PackageStatus.PROCESSING
                && parentPackage.status() != PackageStatus.PENDING_REVIEW
                && parentPackage.status() != PackageStatus.IN_REVIEW) {
            throw new ValidationException(LvErrorCode.PRC_009, "Package is not in a valid status for redaction");
        }

        // 4. Verify document is in REDACTING status
        if (document.status() != DocumentStatus.REDACTING) {
            throw new ValidationException(LvErrorCode.PRC_009, "Document is not in REDACTING status");
        }

        // 5. Load findings for this document (tenant-scoped). DETECTED means human review is
        //    still required; never sweep a low-confidence finding into automatic redaction.
        List<Finding> allFindings = findingRepository.findByDocumentIdAndTenantId(
                command.documentId(), command.tenantId());

        boolean hasUnreviewedFindings = allFindings.stream()
                .anyMatch(f -> f.status() == FindingStatus.DETECTED);
        if (hasUnreviewedFindings) {
            throw new ValidationException(LvErrorCode.REV_003, "Document has findings that still require review");
        }

        List<Finding> confirmedFindings = allFindings.stream()
                .filter(f -> f.status() == FindingStatus.CONFIRMED)
                .toList();

        // 6. Every confirmed finding must have a trusted location. Zero confirmed findings is
        //    valid: the engine still creates a verified output artifact.
        boolean allHaveBounds = confirmedFindings.stream()
                .allMatch(f -> f.location().hasBoundingBox());
        if (!allHaveBounds) {
            // Fail closed (unchanged). Task 0023 C3 (#137): thrown as a typed failure so the caller
            // records a Support incident with the code, instead of a silent document failure.
            throw redactionSafetyFailure(TechnicalFailureCode.REDACTION_LOCATION_MISSING,
                    "Redaction location could not be determined");
        }


        // 9. Load original artifact
        Artifact originalArtifact = artifactRepository
                .findByDocumentIdAndTenantId(document.id(), command.tenantId())
                .stream()
                .filter(a -> a.artifactType() == ArtifactType.ORIGINAL && a.isAvailable())
                .findFirst()
                // Task 0026 (#363): the original is missing from storage, LV-PRC-007.
                .orElseThrow(() -> new NotFoundException("Original artifact for document",
                        command.documentId().toString(), com.securedoc.platform.domain.error.LvErrorCode.PRC_007));

        // Task 0044: the entity's output choice, and the values that were redacted (in memory only, for the final check).
        boolean searchable = policyRepository != null && policyRepository.findByTenantId(command.tenantId())
                .map(TenantRedactionPolicy::searchableRedactedPages).orElse(true);
        java.util.Map<Finding, FoundValue> foundValues = readFoundValues(originalArtifact, confirmedFindings);
        RedactedValues redactedValues = RedactedValues.of(foundValues.values().stream().map(FoundValue::raw).toList());

        // 10. Build targets only from policy-confirmed findings (task 0062: with the last four digits where they stay).
        List<RedactionTarget> targets = keepLastFour(confirmedFindings, buildRedactionTargets(confirmedFindings),
                foundValues);
        long keptLastFour = targets.stream().filter(t -> t.kept() != null).count();

        // 10. Read original PDF content
        InputStream pdfContent = storagePort.retrieve(originalArtifact.storageReference());

        // 11. Perform selective permanent redaction. Target pages are burn-flattened;
        //     safe untouched pages may retain native PDF content.
        RedactionOutput redactionOutput;
        try {
            redactionOutput = searchable
                    ? pdfRedactionPort.redact(pdfContent, targets, PdfRedactionPort.RedactionOptions.SEARCHABLE)
                    : pdfRedactionPort.redact(pdfContent, targets);
        } finally {
            closeQuietly(pdfContent);
        }

        // 11. Verify the generated artifact before it can be stored/exported. The expected
        //     page count comes from source extraction (Document.pages), not from the redaction
        //     adapter's own result, so a page-dropping redaction defect cannot self-validate.
        int expectedSourcePageCount = document.pageCount();

        // Task 0023: the checks run inside the try so the redacted temp output is released on
        // every path (it was left on disk when a check failed), and each check keeps its code.
        try (var redactedContent = redactionOutput.redactedPdfContent()) {
            if (expectedSourcePageCount < 1) {
                throw redactionSafetyFailure(TechnicalFailureCode.REDACTION_SOURCE_PAGES_UNKNOWN,
                        "Source page count is unavailable for redaction verification");
            }
            if (redactionOutput.pagesRendered() != expectedSourcePageCount) {
                throw redactionSafetyFailure(TechnicalFailureCode.REDACTION_PAGE_COUNT_MISMATCH,
                        "Redaction output page count does not match source");
            }
            if (redactionOutput.redactionsApplied() != targets.size()) {
                throw redactionSafetyFailure(TechnicalFailureCode.REDACTION_TARGETS_NOT_APPLIED,
                        "Redaction engine did not apply every expected target");
            }

            long redactedSize = redactedContent.contentLength();
            RedactionVerificationPort.VerificationResult verification;
            InputStream verificationStream = redactedContent.openStream();
            try {
                verification = searchable || !redactedValues.isEmpty()
                        ? redactionVerificationPort.verify(verificationStream, expectedSourcePageCount, targets,
                                searchable, redactedValues)
                        : redactionVerificationPort.verify(verificationStream, expectedSourcePageCount, targets);
            } finally {
                closeQuietly(verificationStream);
            }
            if (!verification.safe()) {
                // Task 0023 C3 (#142): keep the exact failed check as a code (was one generic UNKNOWN).
                TechnicalFailureCode code = verification.failureCode() == null
                        ? TechnicalFailureCode.REDACTION_OUTPUT_UNPARSABLE : verification.failureCode();
                log.warn("DOCUMENT_REDACTION_VERIFICATION_FAILED document={} package={} reasonCode={} pages={} expectedRedactions={} coveredRedactions={}",
                        command.documentId(), command.packageId(), code, verification.pageCount(),
                        verification.expectedRedactionCount(), verification.coveredRedactionCount());
                throw redactionSafetyFailure(code, "Redacted output failed security verification");
            }
            auditService.recordSuccess(
                    command.tenantId(), SYSTEM_ACTOR,
                    AuditAction.DOCUMENT_REDACTION_VERIFIED, "Document", command.documentId().toString(), command.packageId(),
                    correlationId,
                    "Redaction verified: pages=" + verification.pageCount()
                            + ", expectedRedactions=" + verification.expectedRedactionCount()
                            + ", coveredRedactions=" + verification.coveredRedactionCount()
                            + (searchable
                                    ? ", redactedPagesText=OCR_LAYER, valuesChecked=" + redactedValues.size()
                                    : ", redactedPagesExtractableText=false"
                                            + (redactedValues.isEmpty() ? "" : ", valuesChecked=" + redactedValues.size())));

            if (redactionOutput.signatureRemoved()) {
                // Task 0050: no signer name. The case is the record's resource, so the case panel can ask for it.
                auditService.recordSuccess(command.tenantId(), SYSTEM_ACTOR, AuditAction.DOCUMENT_SIGNATURE_REMOVED,
                        "DocumentPackage", command.packageId().toString(), command.packageId(), correlationId,
                        "Signature removed by redaction: document=" + command.documentId());
            }

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
                    command.documentId().toString(), command.packageId(),
                    correlationId,
                    "Redaction completed: redactions=" + redactionOutput.redactionsApplied()
                            + ", pages=" + redactionOutput.pagesRendered()
                            + ", artifactSize=" + redactedSize
                            + (keptLastFour == 0 ? "" : ", keptLastFour=" + keptLastFour)
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

    /**
     * Task 0044: the values the confirmed findings cover, read again from the original with the same text extraction
     * the detection used (findings keep only offsets, never a value: task 0015). Held in memory for the final check
     * and dropped with this call; never saved or logged. None without the extraction port or without findings.
     * Task 0047: only the pages with findings are read again (the values are only there); the final check still
     * compares every output page's text with them.
     */
    private java.util.Map<Finding, FoundValue> readFoundValues(Artifact originalArtifact, List<Finding> confirmedFindings) {
        if (textExtractionPort == null || confirmedFindings.isEmpty()) return java.util.Map.of();
        java.util.Set<Integer> pagesWithFindings = new java.util.TreeSet<>();
        for (Finding finding : confirmedFindings) pagesWithFindings.add(finding.location().pageNumber());
        InputStream original = storagePort.retrieve(originalArtifact.storageReference());
        TextExtractionPort.TextExtractionOutput extracted;
        try {
            extracted = textExtractionPort.extractPages(original, REDACTED_MIME_TYPE, pagesWithFindings);
        } finally {
            closeQuietly(original);
        }
        java.util.Map<Integer, TextExtractionPort.PageText> pages = new java.util.HashMap<>();
        for (TextExtractionPort.PageText page : extracted.pages()) pages.put(page.pageNumber(), page);
        java.util.Map<Finding, FoundValue> values = new java.util.IdentityHashMap<>();
        for (Finding finding : confirmedFindings) {
            FindingLocation location = finding.location();
            TextExtractionPort.PageText page = pages.get(location.pageNumber());
            if (page == null) continue;
            String text = page.text();
            int start = location.startOffset();
            int end = location.endOffset();
            if (start >= 0 && end > start && end <= text.length()) {
                values.put(finding, new FoundValue(text.substring(start, end),
                        page.source() == TextExtractionPort.ExtractionSource.NATIVE_PDF_TEXT));
            }
        }
        return values;
    }

    /** A value read again from the original, in memory for this redaction only; never printed. */
    private record FoundValue(String raw, boolean exact) {
        @Override
        public String toString() {
            return "FoundValue[exact=" + exact + "]";
        }
    }

    /** At least this many letters and digits, so at least as many stay covered as are shown. */
    private static final int KEEP_LAST_FOUR_MINIMUM = 8;
    /** Numbers of exactly nine digits. */
    private static final java.util.Set<com.securedoc.platform.domain.finding.EntityType> NINE_DIGITS = java.util.Set.of(
            com.securedoc.platform.domain.finding.EntityType.SSN, com.securedoc.platform.domain.finding.EntityType.ITIN,
            com.securedoc.platform.domain.finding.EntityType.EIN,
            com.securedoc.platform.domain.finding.EntityType.BANK_ROUTING_NUMBER);

    /**
     * Task 0062, style "keep the last 4". The digits are taken from the original here, at redaction time. Anything not
     * certain is removed in full: a locked item; text read by OCR (a misread digit would be drawn as fact); a value
     * that is too short, goes over a line, or doesn't end in four digits; a box that touches another redaction or is
     * not a single line of text; and four digits that are themselves a redacted value elsewhere in the document.
     */
    private static List<RedactionTarget> keepLastFour(List<Finding> findings, List<RedactionTarget> targets,
                                                      java.util.Map<Finding, FoundValue> values) {
        if (findings.size() != targets.size()) return targets;
        RedactedValues removed = RedactedValues.of(findings.stream()
                .filter(f -> !wantsLastFour(f) && values.containsKey(f)).map(f -> values.get(f).raw()).toList());
        List<RedactionTarget> out = new ArrayList<>(targets.size());
        for (int n = 0; n < findings.size(); n++) {
            RedactionTarget target = targets.get(n);
            FoundValue value = values.get(findings.get(n));
            boolean alone = targets.stream().noneMatch(t -> t != target && t.intersects(target));
            if (wantsLastFour(findings.get(n)) && value != null && value.exact() && alone
                    && target.width() > target.height()) {
                String raw = value.raw().strip();
                String compared = RedactedValues.comparable(raw);
                if (raw.indexOf('\n') < 0 && raw.indexOf('\r') < 0
                        && compared.length() >= KEEP_LAST_FOUR_MINIMUM && raw.matches("(?s).*[0-9]{4}")
                        // A span that holds only part of such a number would show digits that are not its last four.
                        && (!NINE_DIGITS.contains(findings.get(n).entityType())
                                || raw.chars().filter(c -> c >= '0' && c <= '9').count() == 9)) {
                    String lastFour = raw.substring(raw.length() - 4);
                    if (!removed.appearsIn(lastFour)) {
                        out.add(target.keeping(new PdfRedactionPort.KeptDigits(lastFour,
                                Math.min(0.5, 4.0 / raw.length()))));
                        continue;
                    }
                }
            }
            out.add(target);
        }
        return out;
    }

    private static boolean wantsLastFour(Finding finding) {
        return finding.redactionStyle() == com.securedoc.platform.domain.business.RedactionStyle.KEEP_LAST_FOUR
                && com.securedoc.platform.domain.business.RedactionCatalog.lastFourAllowed().contains(finding.entityType());
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
        // Task 0023 C3: the audit carries the classified code ("Redaction failed: dependency=…,
        // code=…, retryable=…, attempts=…"), which the case panel parses; the event keeps a fixed
        // sentence. Neither carries exception text.
        String eventReason = TechnicalFailureClassifier.classify(e).failureCode()
                == TechnicalFailureCode.REDACTION_LOCATION_MISSING
                ? FailureReasonSanitizer.sanitize("Redaction location could not be determined")
                : FailureReasonSanitizer.redactionFailed();
        failDocument(command, correlationId, eventReason,
                TechnicalFailureClassifier.safeAuditDetail(e, FailureReasonSanitizer.redactionFailed()));
    }

    /** Task 0023 C3: a fail-closed redaction safety stop with its exact code. Never retried. */
    private static TechnicalDependencyException redactionSafetyFailure(TechnicalFailureCode code, String safeMessage) {
        return new TechnicalDependencyException(TechnicalDependency.REDACTION, code, false, safeMessage);
    }

    /**
     * Marks the document as FAILED, publishes failure event, and audits.
     * Used both for safety fail-closed (no bounding boxes) and real errors.
     */
    private void failDocument(RedactDocumentCommand command, CorrelationId correlationId, String reason,
                              String auditDetail) {
        // Mark document failed
        try {
            documentRepository.findByIdAndTenantId(command.documentId(), command.tenantId())
                    .ifPresent(doc -> {
                        doc.fail();
                        documentRepository.save(doc);
                    });
        } catch (Exception stepFailure) {
            // Best-effort. Task 0023: no longer silent; type only, never the message.
            logFailStep(command, "MARK_FAILED", stepFailure);
        }

        // Publish failure event
        try {
            RedactionFailedEvent event = RedactionFailedEvent.of(
                    command.documentId(), command.packageId(), command.tenantId(),
                    reason, correlationId);
            eventPublisher.publish(event);
        } catch (Exception stepFailure) {
            logFailStep(command, "PUBLISH_EVENT", stepFailure);
        }

        // Audit
        try {
            auditService.recordFailure(
                    command.tenantId(),
                    SYSTEM_ACTOR,
                    AuditAction.DOCUMENT_PROCESSING_FAILED,
                    "Document",
                    command.documentId().toString(), command.packageId(),
                    correlationId,
                    auditDetail
            );
        } catch (Exception stepFailure) {
            logFailStep(command, "AUDIT", stepFailure);
        }
    }

    private static void logFailStep(RedactDocumentCommand command, String step, Exception failure) {
        log.warn("DOCUMENT_REDACTION_FAIL_STEP_FAILED document={} package={} step={} failureType={}",
                command.documentId(), command.packageId(), step, failure.getClass().getSimpleName());
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
