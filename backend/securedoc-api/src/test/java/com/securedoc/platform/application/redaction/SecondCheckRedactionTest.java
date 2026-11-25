package com.securedoc.platform.application.redaction;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.ChecksumPort;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.DomainEventPublisher;
import com.securedoc.platform.application.port.out.FindingRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.AnalysisOutput;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.AnalysisRequest;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.DiscoveredSensitiveContent;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.application.redaction.command.RedactDocumentCommand;
import com.securedoc.platform.application.redaction.result.RedactDocumentResult;
import com.securedoc.platform.application.redaction.service.DocumentRedactionService;
import com.securedoc.platform.application.secondcheck.SecondCheck;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactStorageReference;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.business.PageBatchSettings;
import com.securedoc.platform.domain.business.ReviewMode;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.business.VisionSettings;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.document.DocumentType;
import com.securedoc.platform.domain.document.PackageMetadata;
import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.document.Page;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.finding.AiModel;
import com.securedoc.platform.domain.finding.DetectorSource;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.finding.FindingLocation;
import com.securedoc.platform.domain.finding.FindingLocation.BoundingBox;
import com.securedoc.platform.domain.finding.FindingStatus;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.vision.VisionPageResult;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.Confidence;
import com.securedoc.platform.domain.vo.FileName;
import com.securedoc.platform.domain.vo.MimeType;
import com.securedoc.platform.infrastructure.extraction.PdfBoxTextExtractionAdapter;
import com.securedoc.platform.infrastructure.extraction.PdfPageImageAdapter;
import com.securedoc.platform.infrastructure.redaction.PdfBoxBurnFlatRedactionAdapter;
import com.securedoc.platform.infrastructure.redaction.PdfBoxRedactionVerificationAdapter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 0083 through the redaction service, with a mock AI: page 1 has one redacted SSN, page 2 holds an SSN that Find
 * missed. Fake values only.
 */
class SecondCheckRedactionTest {
    private static final String FOUND = "412-55-7531";
    private static final String MISSED = "518-22-7741";
    private static final AiModel TEXT = AiModel.of("text:1", "text-second-check-text-v1", "aa");
    private static final AiModel VISION = AiModel.of("vl:1", "second-check-vision-v1", "bb");

    private final TenantId tenant = TenantId.generate();
    private final DocumentPackage pkg = processingPackage(tenant);
    private Document document;
    private final List<Finding> stored = new ArrayList<>();
    private final List<AuditRecord> audits = new ArrayList<>();
    private final List<byte[]> outputs = new ArrayList<>();
    private final List<AnalysisRequest> asked = new ArrayList<>();
    private final List<Integer> pagesSeen = new ArrayList<>();
    private final List<Integer> counted = new ArrayList<>();
    private byte[] source;
    private TenantRedactionPolicy policy;
    private RuntimeException textFails;
    private List<DiscoveredSensitiveContent> textAnswer = List.of();
    private VisionPageResult visionAnswer;
    private ListAppender<ILoggingEvent> logs;
    private Logger root;
    private Level level;

    @BeforeEach
    void setUp() throws Exception {
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        level = root.getLevel();
        root.setLevel(Level.ALL);
        logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        source = pdf();
        document = Document.reconstitute(DocumentId.generate(), tenant, pkg.id(), FileName.of("a.pdf"), MimeType.pdf(),
                64, Checksum.sha256("a"), DocumentType.PDF, Instant.now(), DocumentStatus.REDACTING,
                List.of(Page.of(1, 612, 792), Page.of(2, 612, 792)), Instant.now());
        policy = policy(true, true, Set.of(EntityType.SSN));
        visionAnswer = VisionPageResult.read(1, VISION, List.of(), false);
        stored.add(finding(1, FOUND, FindingStatus.CONFIRMED));
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(logs);
        root.setLevel(level);
        for (String text : java.util.stream.Stream.concat(audits.stream().map(AuditRecord::detail),
                logs.list.stream().map(ILoggingEvent::getFormattedMessage)).toList()) {
            for (String value : List.of(FOUND, MISSED)) {
                assertFalse(text != null && (text.contains(value) || text.contains(value.replace("-", ""))),
                        "an audit record or log line holds a value");
            }
        }
        for (Finding finding : stored) {
            assertFalse(finding.detectorSource().toString().contains("22-7741"));
        }
    }

    @Test
    void aMissedValueSendsItsPageToAPersonAndNothingIsStored() {
        textAnswer = List.of(hit("SSN", 2, MISSED));
        RedactDocumentResult result = redact();

        assertNull(result.redactedArtifactId());
        assertEquals(DocumentStatus.REDACTING, document.status());
        assertTrue(outputs.isEmpty() && counted.isEmpty(), "nothing stored, nothing counted");
        Finding page = onlyPageToCheck();
        assertEquals(2, page.location().pageNumber());
        assertFalse(page.location().hasBoundingBox());
        assertEquals(FindingStatus.DETECTED, page.status());
        assertEquals(List.of("SSN"), SecondCheck.kindsOf(page));
        assertEquals(List.of(VISION, TEXT), page.origin().models(), "page 1 is an image, page 2 has text");
        assertEquals(FindingStatus.CONFIRMED, stored.get(0).status(), "not marked as redacted");
        assertEquals("Second check: pages=2, checkedByAPersonBefore=0, pagesToCheck=1, found=[p2:SSN], aiModels=["
                + VISION.encode() + ";" + TEXT.encode() + "]", audit().detail());
        assertTrue(asked.stream().allMatch(r -> r.policy().secondCheck() && r.pages().size() == 1));
        assertEquals(List.of(1), pagesSeen, "the image of the redacted page only; page 2 has text of its own");
    }

    /** Task 0092: the model says nothing about it. */
    @Test
    void aCheckLineStillToBeSeenSendsItsPageToAPerson() {
        policy = policy(true, true, Set.of(EntityType.SSN, EntityType.BANK_ACCOUNT_NUMBER, EntityType.BANK_ROUTING_NUMBER));
        lines = List.of(new com.securedoc.platform.application.port.out.PageImagePort.CheckLine(
                com.securedoc.platform.domain.finding.FindingLocation.BoundingBox.of(0.2, 0.9, 0.1, 0.02),
                com.securedoc.platform.domain.finding.FindingLocation.BoundingBox.of(0.4, 0.9, 0.1, 0.02)));
        redact();
        assertEquals(List.of("ACCOUNT", "ROUTING"), SecondCheck.kindsOf(onlyPageToCheck()));
        assertEquals(1, onlyPageToCheck().location().pageNumber());
    }

    private List<com.securedoc.platform.application.port.out.PageImagePort.CheckLine> lines = List.of();

    @Test
    void afterAPersonCheckedThePageTheDocumentGoesOnWithoutAskingAgain() {
        textAnswer = List.of(hit("SSN", 2, MISSED));
        redact();
        onlyPageToCheck().dismiss(ActorId.generate(), "Page reviewed by hand");
        redact();
        assertEquals(DocumentStatus.PROCESSED, document.status());
        assertEquals(1, outputs.size());
        assertTrue(audit().detail().startsWith("Second check: pages=1, checkedByAPersonBefore=1, pagesToCheck=0"));
    }

    @Test
    void aCleanFileIsStoredAndTheCheckIsOnTheRecord() {
        redact();
        assertEquals(DocumentStatus.PROCESSED, document.status());
        assertEquals(1, outputs.size());
        assertTrue(audit().detail().contains("pages=2") && audit().detail().contains(VISION.encode()));
    }

    @Test
    void offAsksNoModelAndRecordsNothing() {
        policy = policy(false, true, Set.of(EntityType.SSN));
        textAnswer = List.of(hit("SSN", 2, MISSED));
        redact();
        assertEquals(DocumentStatus.PROCESSED, document.status());
        assertTrue(asked.isEmpty() && pagesSeen.isEmpty());
        assertTrue(audits.stream().noneMatch(a -> a.action() == AuditAction.DOCUMENT_SECOND_CHECK));
    }

    @Test
    void onWithoutACheckIsNeverPassedAsChecked() {
        assertThrows(IllegalStateException.class, () -> service().execute(command()));
        assertTrue(outputs.isEmpty());
    }

    @Test
    void whatAPersonKeptIsNoHitNorIsAKindTheEntityDoesNotRedactNorAWrongShape() {
        stored.add(finding(2, MISSED, FindingStatus.DISMISSED));
        textAnswer = List.of(hit("SSN", 2, MISSED), hit("BANK_ACCOUNT", 2, MISSED), hit("CREDIT_CARD_NUMBER", 2, "7741"),
                hit("SOMETHING", 2, "x"));
        redact();
        assertEquals(DocumentStatus.PROCESSED, document.status());
    }

    @Test
    void aBoxOnACoverIsNoHitAndOneOnThePaperIs() {
        policy = policy(true, true, Set.of(EntityType.SSN, EntityType.FACE_PHOTO));
        BoundingBox cover = stored.get(0).location().boundingBox();
        visionAnswer = VisionPageResult.read(1, VISION, List.of(item(VisionPageResult.Kind.SSN, cover),
                item(VisionPageResult.Kind.SIGNATURE, BoundingBox.of(0.5, 0.5, 0.2, 0.05))), false);
        redact();
        assertEquals(DocumentStatus.PROCESSED, document.status());

        reset();
        visionAnswer = VisionPageResult.read(1, VISION, List.of(
                item(VisionPageResult.Kind.FACE_PHOTO, BoundingBox.of(0.5, 0.5, 0.2, 0.2))), false);
        redact();
        assertEquals(List.of("FACE"), SecondCheck.kindsOf(onlyPageToCheck()));
    }

    @Test
    void withVisionOffOnlyTextIsRead() {
        policy = policy(true, false, Set.of(EntityType.SSN));
        redact();
        assertTrue(pagesSeen.isEmpty());
        assertEquals(DocumentStatus.PROCESSED, document.status());
    }

    @Test
    void anAnswerThatCanNotBeUsedSendsThePageToAPerson() {
        textFails = new TechnicalDependencyException(TechnicalDependency.AI, TechnicalFailureCode.AI_INVALID_RESPONSE,
                false, "not usable");
        visionAnswer = VisionPageResult.notRead(1, LvErrorCode.AI_005.code(), "ANSWER_CUT_SHORT");
        redact();
        assertEquals(2, stored.stream().filter(f -> f.entityType() == EntityType.UNREAD_PAGE)
                .filter(f -> List.of("UNREAD").equals(SecondCheck.kindsOf(f))).count());
        assertEquals(DocumentStatus.REDACTING, document.status());
    }

    @Test
    void aModelThatCanNotBeAskedStopsTheDocument() {
        textFails = new TechnicalDependencyException(TechnicalDependency.AI, TechnicalFailureCode.AI_UNAVAILABLE,
                false, "down");
        assertThrows(TechnicalDependencyException.class, this::redact);
        assertEquals(DocumentStatus.FAILED, document.status());
        assertTrue(outputs.isEmpty());

        reset();
        visionAnswer = VisionPageResult.notRead(1, LvErrorCode.AI_008.code(), "MODEL_DIGEST_MISMATCH");
        TechnicalDependencyException refused = assertThrows(TechnicalDependencyException.class, this::redact);
        assertEquals(TechnicalFailureCode.AI_REJECTED, refused.failureCode());
        assertEquals(DocumentStatus.FAILED, document.status());
        assertTrue(outputs.isEmpty());
    }

    @Test
    void everyKindFitsTheColumn() {
        policy = policy(true, true, Set.of(EntityType.SSN, EntityType.DATE_OF_BIRTH, EntityType.CREDIT_CARD_NUMBER,
                EntityType.CREDIT_SCORE, EntityType.BANK_ACCOUNT_NUMBER, EntityType.BANK_ROUTING_NUMBER,
                EntityType.DRIVERS_LICENSE_NUMBER, EntityType.FACE_PHOTO));
        List<VisionPageResult.Item> all = new ArrayList<>();
        for (VisionPageResult.Kind kind : VisionPageResult.Kind.values()) {
            if (kind != VisionPageResult.Kind.CREDIT_SCORE) all.add(item(kind, BoundingBox.of(0.5, 0.5, 0.2, 0.2)));
        }
        visionAnswer = VisionPageResult.read(1, VISION, all, true);
        redact();
        String version = onlyPageToCheck().detectorSource().version();
        assertEquals("SSN,DOB,CARD,ACCOUNT,ROUTING,ID,FACE,UNREAD", version);
        assertTrue((version + ",SCORE").length() <= 50);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static DocumentPackage processingPackage(TenantId tenant) {
        DocumentPackage value = DocumentPackage.create(tenant, PackageType.MANUAL_UPLOAD, ActorId.generate(),
                PackageMetadata.empty());
        value.addDocument(Document.create(tenant, value.id(), FileName.of("a.pdf"), MimeType.pdf(), 64,
                Checksum.sha256("a"), DocumentType.PDF));
        value.submit();
        value.startProcessing();
        return value;
    }

    private void reset() {
        document = Document.reconstitute(document.id(), tenant, pkg.id(), FileName.of("a.pdf"), MimeType.pdf(), 64,
                Checksum.sha256("a"), DocumentType.PDF, Instant.now(), DocumentStatus.REDACTING,
                List.of(Page.of(1, 612, 792), Page.of(2, 612, 792)), Instant.now());
        stored.clear();
        stored.add(finding(1, FOUND, FindingStatus.CONFIRMED));
        outputs.clear();
        textFails = null;
        textAnswer = List.of();
    }

    private RedactDocumentResult redact() {
        SensitiveContentAnalysisPort text = new SensitiveContentAnalysisPort() {
            @Override public AnalysisOutput analyze(AnalysisRequest request) {
                asked.add(request);
                if (textFails != null) throw textFails;
                int page = request.pages().get(0).pageNumber();
                return new AnalysisOutput(textAnswer.stream().filter(a -> a.pageNumber() == page).toList(), List.of(TEXT));
            }
        };
        return service().withSecondCheck(new SecondCheck(new PdfBoxTextExtractionAdapter(), text,
                new PdfPageImageAdapter() {
                    @Override public List<CheckLine> checkLines(InputStream pdf, int page) throws java.io.IOException {
                        assertTrue(super.checkLines(pdf, page).isEmpty(), "no line on these pages");
                        return lines;
                    }
                }, (image, page) -> {
                    pagesSeen.add(page);
                    return visionAnswer;
                })).execute(command());
    }

    private RedactDocumentCommand command() {
        return RedactDocumentCommand.of(document.id(), pkg.id(), tenant, CorrelationId.generate());
    }

    private DocumentRedactionService service() {
        DocumentRepository documents = mock(DocumentRepository.class);
        when(documents.findByIdAndTenantId(document.id(), tenant)).thenReturn(Optional.of(document));
        PackageRepository packages = mock(PackageRepository.class);
        when(packages.findByIdAndTenantId(pkg.id(), tenant)).thenReturn(Optional.of(pkg));
        Artifact original = Artifact.create(tenant, document.id(), ArtifactType.ORIGINAL, MimeType.pdf(), source.length,
                Checksum.sha256("original"));
        original.markAvailable(ArtifactStorageReference.local("t/p/d/ORIGINAL/a"));
        ArtifactRepository artifacts = mock(ArtifactRepository.class);
        when(artifacts.findByDocumentIdAndTenantId(document.id(), tenant)).thenReturn(List.of(original));
        StoragePort storage = mock(StoragePort.class);
        when(storage.retrieve(any())).thenAnswer(i -> new ByteArrayInputStream(source));
        when(storage.storeForPackage(any(), any(), anyString(), any(), anyLong(), anyString())).thenAnswer(i -> {
            outputs.add(((InputStream) i.getArgument(3)).readAllBytes());
            return ArtifactStorageReference.local("t/p/d/REDACTED/r");
        });
        FindingRepository findings = mock(FindingRepository.class);
        when(findings.findByDocumentIdAndTenantId(document.id(), tenant)).thenAnswer(i -> List.copyOf(stored));
        doAnswer(i -> {
            for (Finding f : i.<List<Finding>>getArgument(0)) if (!stored.contains(f)) stored.add(f);
            return null;
        }).when(findings).saveAll(any());
        ChecksumPort checksum = mock(ChecksumPort.class);
        when(checksum.compute(any())).thenReturn(Checksum.sha256("redacted"));
        TenantRedactionPolicyRepository policies = new TenantRedactionPolicyRepository() {
            @Override public Optional<TenantRedactionPolicy> findByTenantId(TenantId t) { return Optional.of(policy); }
            @Override public void save(TenantRedactionPolicy p) { }
        };
        return new DocumentRedactionService(documents, packages, artifacts, findings, storage,
                new PdfBoxBurnFlatRedactionAdapter(), new PdfBoxRedactionVerificationAdapter(), checksum,
                mock(DomainEventPublisher.class), new AuditService(audits::add), policies,
                new PdfBoxTextExtractionAdapter()).withUsageMeter((t, p, d, pages) -> counted.add(pages));
    }

    private Finding onlyPageToCheck() {
        List<Finding> pages = stored.stream().filter(f -> f.entityType() == EntityType.UNREAD_PAGE).toList();
        assertEquals(1, pages.size());
        return pages.get(0);
    }

    /** The newest. */
    private AuditRecord audit() {
        return audits.stream().filter(a -> a.action() == AuditAction.DOCUMENT_SECOND_CHECK).reduce((a, b) -> b)
                .orElseThrow();
    }

    private static VisionPageResult.Item item(VisionPageResult.Kind kind, BoundingBox box) {
        return new VisionPageResult.Item(kind, box);
    }

    private static DiscoveredSensitiveContent hit(String kind, int page, String value) {
        return new DiscoveredSensitiveContent(kind, page, value, "", 0.0, "test", "1");
    }

    private TenantRedactionPolicy policy(boolean check, boolean vision, Set<EntityType> kinds) {
        return new TenantRedactionPolicy(tenant, kinds, 0.8, ReviewMode.UNCERTAIN_ONLY, true, Instant.now(), false,
                PageBatchSettings.DEFAULTS, Set.of(), Set.of(), false, new VisionSettings(vision, 0.85), check);
    }

    private Finding finding(int number, String value, FindingStatus status) {
        var page = new PdfBoxTextExtractionAdapter().extract(new ByteArrayInputStream(source), "application/pdf").pages()
                .get(number - 1);
        int start = page.text().indexOf(value);
        int end = start + value.length();
        double left = 1, top = 1, right = 0, bottom = 0;
        for (var s : page.spans()) {
            if (s.endOffset() <= start || s.startOffset() >= end) continue;
            left = Math.min(left, s.left());
            top = Math.min(top, s.top());
            right = Math.max(right, s.left() + s.width());
            bottom = Math.max(bottom, s.top() + s.height());
        }
        return Finding.reconstitute(com.securedoc.platform.domain.identity.FindingId.generate(), tenant, document.id(),
                EntityType.SSN, FindingLocation.withBounds(number, start, end, BoundingBox.of(left - 0.004, top - 0.004,
                        right - left + 0.008, bottom - top + 0.008)), new Confidence(0.99), DetectorSource.of("test"),
                Instant.now(), status, List.of(), Instant.now(),
                com.securedoc.platform.domain.business.RedactionStyle.REMOVE_FULLY);
    }

    private static byte[] pdf() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String line : List.of("Borrower SSN: " + FOUND, "Co-borrower of the same loan, SSN: " + MISSED)) {
                PDPage page = new PDPage(new PDRectangle(612, 792));
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                    cs.newLineAtOffset(72, 700);
                    cs.showText(line);
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
