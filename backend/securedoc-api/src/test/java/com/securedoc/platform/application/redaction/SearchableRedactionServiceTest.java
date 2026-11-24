package com.securedoc.platform.application.redaction;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.ChecksumPort;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.DomainEventPublisher;
import com.securedoc.platform.application.port.out.FindingRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.PdfRedactionPort;
import com.securedoc.platform.application.port.out.ReplayableContent;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.application.port.out.TextExtractionPort;
import com.securedoc.platform.application.redaction.command.RedactDocumentCommand;
import com.securedoc.platform.application.redaction.service.DocumentRedactionService;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactStorageReference;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.business.RedactionStyle;
import com.securedoc.platform.domain.business.ReviewMode;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.document.DocumentType;
import com.securedoc.platform.domain.document.PackageMetadata;
import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.document.Page;
import com.securedoc.platform.domain.finding.DetectorSource;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.finding.FindingLocation;
import com.securedoc.platform.domain.finding.FindingStatus;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.FindingId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.Confidence;
import com.securedoc.platform.domain.vo.FileName;
import com.securedoc.platform.domain.vo.MimeType;
import com.securedoc.platform.infrastructure.extraction.PdfBoxTextExtractionAdapter;
import com.securedoc.platform.infrastructure.ocr.TesseractOcrAdapter;
import com.securedoc.platform.infrastructure.ocr.TesseractOcrProperties;
import com.securedoc.platform.infrastructure.redaction.PdfBoxBurnFlatRedactionAdapter;
import com.securedoc.platform.infrastructure.redaction.PdfBoxRedactionVerificationAdapter;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 0044 through the redaction service, with the product's engine, final check and text extraction. The values the
 * confirmed findings cover are read again from the original (findings keep offsets only) and compared with the output
 * text in memory. A clean run stores the searchable copy; a planted leak stops the document with LV-RED-005, stores
 * nothing, and no audit record or log line holds any part of a value. Fake values only.
 */
class SearchableRedactionServiceTest {

    private static final String SSN = "412-55-7531";
    private static final String CARD = "4000 0566 5566 5556";
    private static final String DOB = "03/14/1962";
    private static final PDType1Font FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

    private final TenantId tenantId = TenantId.generate();
    private final DocumentPackage pkg = processingPackage();
    private final Document document = redactingDocument();
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final PackageRepository packages = mock(PackageRepository.class);
    private final ArtifactRepository artifacts = mock(ArtifactRepository.class);
    private final FindingRepository findings = mock(FindingRepository.class);
    private final StoragePort storage = mock(StoragePort.class);
    private final ChecksumPort checksum = mock(ChecksumPort.class);
    private final List<AuditRecord> audits = new ArrayList<>();
    private final List<byte[]> stored = new ArrayList<>();
    private byte[] source;
    private TenantRedactionPolicy policy;
    private ListAppender<ILoggingEvent> logs;
    private Logger root;
    private Level rootLevelBefore;

    @BeforeEach
    void setUp() throws Exception {
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        rootLevelBefore = root.getLevel();
        root.setLevel(Level.ALL);
        logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);

        source = sourcePdf();
        when(documents.findByIdAndTenantId(document.id(), tenantId)).thenReturn(Optional.of(document));
        when(packages.findByIdAndTenantId(pkg.id(), tenantId)).thenReturn(Optional.of(pkg));
        Artifact original = Artifact.create(tenantId, document.id(), ArtifactType.ORIGINAL, MimeType.pdf(), source.length,
                Checksum.sha256("original"));
        original.markAvailable(ArtifactStorageReference.local("t/p/d/ORIGINAL/a"));
        when(artifacts.findByDocumentIdAndTenantId(document.id(), tenantId)).thenReturn(List.of(original));
        when(storage.retrieve(any())).thenAnswer(i -> new ByteArrayInputStream(source));
        when(storage.storeForPackage(any(), any(), anyString(), any(), anyLong(), anyString())).thenAnswer(i -> {
            stored.add(((InputStream) i.getArgument(3)).readAllBytes());
            return ArtifactStorageReference.local("t/p/d/REDACTED/r");
        });
        when(checksum.compute(any())).thenReturn(Checksum.sha256("redacted"));
        when(findings.findByDocumentIdAndTenantId(document.id(), tenantId)).thenReturn(confirmedFindings());
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(logs);
        root.setLevel(rootLevelBefore);
    }

    @Test
    void aCleanRunStoresASearchableCopyAndRecordsOnlyCounts() throws Exception {
        policy = policy(true);
        service(new PdfBoxBurnFlatRedactionAdapter(ocr())).execute(command());

        assertEquals(DocumentStatus.PROCESSED, document.status());
        assertEquals(1, stored.size());
        try (PDDocument out = Loader.loadPDF(stored.get(0))) {
            String text = new PDFTextStripper().getText(out).toLowerCase();
            assertTrue(text.contains("borrower") && text.contains("income"), "the redacted page is searchable");
        }
        AuditRecord verified = audits.stream().filter(a -> a.action() == AuditAction.DOCUMENT_REDACTION_VERIFIED)
                .findFirst().orElseThrow();
        assertTrue(verified.detail().endsWith("redactedPagesText=OCR_LAYER, valuesChecked=3"), verified.detail());
        assertNoValueAnywhere();
    }

    @Test
    void offStoresTodaysImageOnlyCopyAndStillComparesTheValues() throws Exception {
        policy = policy(false);
        service(new PdfBoxBurnFlatRedactionAdapter(ocr())).execute(command());

        assertEquals(DocumentStatus.PROCESSED, document.status());
        try (PDDocument out = Loader.loadPDF(stored.get(0))) {
            PDFTextStripper page1 = new PDFTextStripper();
            page1.setStartPage(1);
            page1.setEndPage(1);
            assertTrue(page1.getText(out).isBlank(), "image only, as today");
        }
        AuditRecord verified = audits.stream().filter(a -> a.action() == AuditAction.DOCUMENT_REDACTION_VERIFIED)
                .findFirst().orElseThrow();
        assertTrue(verified.detail().endsWith("redactedPagesExtractableText=false, valuesChecked=3"), verified.detail());
    }

    @Test
    void aPlantedLeakStopsTheDocumentWithItsOwnCodeAndStoresNothing() {
        policy = policy(true);
        PdfRedactionPort leaking = new LeakingEngine(new PdfBoxBurnFlatRedactionAdapter(ocr()));

        TechnicalDependencyException failure = assertThrows(TechnicalDependencyException.class,
                () -> service(leaking).execute(command()));

        assertEquals(TechnicalFailureCode.REDACTION_VALUE_IN_OUTPUT_TEXT, failure.failureCode());
        assertFalse(failure.retryable());
        assertEquals(DocumentStatus.FAILED, document.status());
        assertTrue(stored.isEmpty(), "fail closed: no redacted copy is stored, so nothing can be exported");
        AuditRecord failed = audits.stream().filter(a -> a.action() == AuditAction.DOCUMENT_PROCESSING_FAILED)
                .findFirst().orElseThrow();
        assertTrue(failed.detail().startsWith("Redaction failed: code=LV-RED-005, detail=REDACTION_VALUE_IN_OUTPUT_TEXT"),
                failed.detail());
        assertNoValueAnywhere();
    }

    /**
     * Task 0047, item 2: the original is read again only on the pages with findings, and every output page is still
     * compared: the SSN printed again on page 2 (no finding there, so page 2 is not read again and not redacted) stops
     * the document with LV-RED-005.
     */
    @Test
    void onlyPagesWithFindingsAreReadAgainAndAValueCopiedOntoAnotherPageStillStopsTheDocument() throws Exception {
        policy = policy(true);
        source = sourcePdf(true);
        Document twoPages = Document.reconstitute(document.id(), tenantId, pkg.id(), FileName.of("a.pdf"), MimeType.pdf(),
                64, Checksum.sha256("a"), DocumentType.PDF, Instant.now(), DocumentStatus.REDACTING,
                List.of(Page.of(1, 612, 792), Page.of(2, 612, 792)), Instant.now());
        when(documents.findByIdAndTenantId(document.id(), tenantId)).thenReturn(Optional.of(twoPages));
        when(findings.findByDocumentIdAndTenantId(document.id(), tenantId)).thenReturn(confirmedFindings());
        List<java.util.Set<Integer>> asked = new ArrayList<>();
        PdfBoxTextExtractionAdapter real = new PdfBoxTextExtractionAdapter();
        TextExtractionPort recording = new TextExtractionPort() {
            @Override public TextExtractionOutput extract(InputStream content, String mimeType) {
                asked.add(null);
                return real.extract(content, mimeType);
            }
            @Override public TextExtractionOutput extractPages(InputStream content, String mimeType,
                                                               java.util.Set<Integer> pageNumbers) {
                asked.add(java.util.Set.copyOf(pageNumbers));
                return real.extractPages(content, mimeType, pageNumbers);
            }
        };

        TechnicalDependencyException failure = assertThrows(TechnicalDependencyException.class,
                () -> service(new PdfBoxBurnFlatRedactionAdapter(ocr()), recording).execute(command()));

        assertEquals(List.of(java.util.Set.of(1)), asked, "only page 1 (the page with findings) is read again");
        assertEquals(TechnicalFailureCode.REDACTION_VALUE_IN_OUTPUT_TEXT, failure.failureCode());
        assertEquals(DocumentStatus.FAILED, twoPages.status());
        assertTrue(stored.isEmpty(), "fail closed: nothing is stored");
        assertNoValueAnywhere();
    }

    /** Task 0050, item 4: recorded once on the case, with ids only; an unsigned document records nothing. */
    @Test
    void aSignatureRemovedByRedactionIsRecordedOnTheCaseWithIdsOnly() {
        policy = policy(true);
        PdfBoxBurnFlatRedactionAdapter real = new PdfBoxBurnFlatRedactionAdapter(ocr());
        PdfRedactionPort signed = new PdfRedactionPort() {
            @Override public RedactionOutput redact(InputStream originalPdf, List<RedactionTarget> targets) {
                return redact(originalPdf, targets, RedactionOptions.IMAGE_ONLY);
            }
            @Override public RedactionOutput redact(InputStream originalPdf, List<RedactionTarget> targets,
                                                    RedactionOptions options) {
                RedactionOutput out = real.redact(originalPdf, targets, options);
                return new RedactionOutput(out.redactedPdfContent(), out.pagesRendered(), out.redactionsApplied(), true);
            }
        };
        service(signed).execute(command());
        List<AuditRecord> removed = audits.stream()
                .filter(a -> a.action() == AuditAction.DOCUMENT_SIGNATURE_REMOVED).toList();
        assertEquals(1, removed.size());
        assertEquals("Signature removed by redaction: document=" + document.id(), removed.get(0).detail());
        assertEquals(pkg.id().toString(), removed.get(0).resourceId());
    }

    /**
     * Task 0062, style "keep the last 4": only the SSN's last four digits can be read on the redacted page (the OCR of
     * the output says so), the card is covered in full, and neither the digits nor the value are in any record or log.
     */
    @Test
    void keepLastFourDrawsTheDigitsFromTheOriginalAndSavesThemNowhere() throws Exception {
        policy = policy(true);
        when(findings.findByDocumentIdAndTenantId(document.id(), tenantId)).thenReturn(
                confirmedFindings(EntityType.SSN, EntityType.CREDIT_CARD_NUMBER, EntityType.DATE_OF_BIRTH));
        service(new PdfBoxBurnFlatRedactionAdapter(ocr())).execute(command());

        assertEquals(DocumentStatus.PROCESSED, document.status());
        BufferedImage page = render(stored.get(0));
        Rectangle box = box(0, page);
        // The left of the cover is solid; the end shows digits (white on black); nothing is outside the cover.
        assertEquals(0, light(page, new Rectangle(box.x + 2, box.y + 2, box.width * 6 / 11 - 2, box.height - 4)));
        int lit = light(page, new Rectangle(box.x + box.width * 7 / 11, box.y + 2, box.width * 4 / 11 - 2, box.height - 4));
        assertTrue(lit > 20, "digits are drawn: " + lit);
        for (int other : List.of(1, 2)) {
            Rectangle full = box(other, page);
            assertEquals(0, light(page, new Rectangle(full.x + 2, full.y + 2, full.width - 4, full.height - 4)),
                    "the card (locked) and the date of birth are covered in full");
        }
        String shown = ocrOf(page.getSubimage(box.x + box.width * 6 / 11, box.y, box.width * 5 / 11, box.height));
        assertEquals("7531", shown.replaceAll("[^0-9]", ""), "exactly the last four digits");

        AuditRecord completed = audits.stream().filter(a -> a.action() == AuditAction.DOCUMENT_REDACTION_COMPLETED)
                .findFirst().orElseThrow();
        assertTrue(completed.detail().endsWith(", keptLastFour=1"), completed.detail());
        assertNoValueAnywhere();
        List<String> texts = new ArrayList<>();
        audits.forEach(a -> texts.add(String.valueOf(a.detail())));
        logs.list.forEach(l -> texts.add(l.getFormattedMessage()));
        for (String text : texts) {
            String withoutIds = text.replaceAll("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}", "");
            assertFalse(withoutIds.matches("(?s).*(?<!\\d)7531(?!\\d).*"), "the kept digits are in no record or log line");
        }
    }

    /** Anything uncertain is removed in full: a scanned page, a touching box, digits redacted elsewhere, no offsets. */
    @Test
    void keepLastFourFallsBackToRemovingInFull() throws Exception {
        policy = policy(false);
        PdfBoxTextExtractionAdapter real = new PdfBoxTextExtractionAdapter();
        TextExtractionPort scanned = (content, mime) -> {
            TextExtractionPort.TextExtractionOutput out = real.extract(content, mime);
            return new TextExtractionPort.TextExtractionOutput(out.pages().stream().map(p ->
                    new TextExtractionPort.PageText(p.pageNumber(), p.width(), p.height(), p.text(), p.spans(),
                            TextExtractionPort.ExtractionSource.OCR, 0.99)).toList(), out.totalCharacterCount(), false, true);
        };
        List<Finding> kept = confirmedFindings(EntityType.SSN, EntityType.DATE_OF_BIRTH, EntityType.DATE_OF_BIRTH);
        assertPlainCover(kept, scanned, "read by OCR");

        List<Finding> touching = new ArrayList<>(kept);
        Finding ssn = kept.get(0);
        touching.add(Finding.reconstitute(FindingId.generate(), tenantId, document.id(), EntityType.PIN, ssn.location(),
                new Confidence(0.99), DetectorSource.of("test"), Instant.now(), FindingStatus.CONFIRMED, List.of(),
                Instant.now()));
        assertPlainCover(touching, real, "another redaction on the same place");

        List<Finding> manual = List.of(Finding.reconstitute(FindingId.generate(), tenantId, document.id(), EntityType.SSN,
                FindingLocation.withBounds(1, 0, 0, ssn.location().boundingBox()), new Confidence(1.0),
                DetectorSource.of("manual"), Instant.now(), FindingStatus.CONFIRMED, List.of(), Instant.now(),
                RedactionStyle.KEEP_LAST_FOUR));
        assertPlainCover(manual, real, "marked by hand: no text to take the digits from");

        String lockedStyle = "a stored style never reaches a locked item";
        List<Finding> locked = List.of(Finding.reconstitute(FindingId.generate(), tenantId, document.id(),
                EntityType.CREDIT_CARD_NUMBER, ssn.location(), new Confidence(1.0), DetectorSource.of("test"),
                Instant.now(), FindingStatus.CONFIRMED, List.of(), Instant.now(), RedactionStyle.KEEP_LAST_FOUR));
        assertPlainCover(locked, real, lockedStyle);
    }

    /** The four digits are a redacted value elsewhere (a labelled last-four SSN): they are not shown here either. */
    @Test
    void digitsThatAreARedactedValueElsewhereAreNotKept() throws Exception {
        policy = policy(false);
        source = sourcePdf(List.of("Borrower SSN: " + SSN, "SSN ending: 7531"));
        TextExtractionPort.PageText page = new PdfBoxTextExtractionAdapter()
                .extract(new ByteArrayInputStream(source), "application/pdf").pages().get(0);
        List<Finding> both = List.of(finding(page, SSN, 0, EntityType.SSN, RedactionStyle.KEEP_LAST_FOUR),
                finding(page, "7531", page.text().indexOf("ending"), EntityType.PIN, RedactionStyle.REMOVE_FULLY));
        assertPlainCover(both, new PdfBoxTextExtractionAdapter(), "redacted elsewhere");
    }

    private void assertPlainCover(List<Finding> confirmed, TextExtractionPort extraction, String why) throws Exception {
        stored.clear();
        audits.clear();
        Document fresh = Document.reconstitute(document.id(), tenantId, pkg.id(), FileName.of("a.pdf"), MimeType.pdf(),
                64, Checksum.sha256("a"), DocumentType.PDF, Instant.now(), DocumentStatus.REDACTING,
                List.of(Page.of(1, 612, 792)), Instant.now());
        when(documents.findByIdAndTenantId(document.id(), tenantId)).thenReturn(Optional.of(fresh));
        when(findings.findByDocumentIdAndTenantId(document.id(), tenantId)).thenReturn(confirmed);
        service(new PdfBoxBurnFlatRedactionAdapter(ocr()), extraction).execute(command());
        BufferedImage image = render(stored.get(0));
        FindingLocation.BoundingBox b = confirmed.get(0).location().boundingBox();
        Rectangle box = rectangle(b, image);
        assertEquals(0, light(image, new Rectangle(box.x + 2, box.y + 2, box.width - 4, box.height - 4)), why);
        assertTrue(audits.stream().noneMatch(a -> String.valueOf(a.detail()).contains("keptLastFour")), why);
        for (Finding f : confirmed) {
            // markRedacted changed them: the next case needs them confirmed again.
            org.springframework.test.util.ReflectionTestUtils.setField(f, "status", FindingStatus.CONFIRMED);
        }
    }

    private Rectangle box(int finding, BufferedImage image) {
        return rectangle(confirmedFindings().get(finding).location().boundingBox(), image);
    }

    private static Rectangle rectangle(FindingLocation.BoundingBox b, BufferedImage image) {
        return new Rectangle((int) Math.round(b.left() * image.getWidth()), (int) Math.round(b.top() * image.getHeight()),
                (int) Math.round(b.width() * image.getWidth()), (int) Math.round(b.height() * image.getHeight()));
    }

    private static int light(BufferedImage image, Rectangle area) {
        int count = 0;
        for (int y = area.y; y < area.y + area.height; y++) {
            for (int x = area.x; x < area.x + area.width; x++) {
                if ((image.getRGB(x, y) & 0xFF) > 128) count++;
            }
        }
        return count;
    }

    private static BufferedImage render(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new org.apache.pdfbox.rendering.PDFRenderer(doc).renderImageWithDPI(0, 150);
        }
    }

    /** Black on white and enlarged, so the reading of four digits does not depend on white-on-black OCR. */
    private static String ocrOf(BufferedImage part) throws Exception {
        int scale = 4, edge = 40;
        BufferedImage big = new BufferedImage(part.getWidth() * scale + 2 * edge, part.getHeight() * scale + 2 * edge,
                BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = big.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(part, edge, edge, part.getWidth() * scale, part.getHeight() * scale, null);
        g.dispose();
        for (int y = 0; y < big.getHeight(); y++) {
            for (int x = 0; x < big.getWidth(); x++) big.setRGB(x, y, ~big.getRGB(x, y) & 0xFFFFFF);
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(big, "png", png);
        return ocr().recognize(new com.securedoc.platform.application.port.out.OcrPort.OcrPageInput(1, png.toByteArray(),
                "image/png", big.getWidth(), big.getHeight())).text();
    }

    // ------------------------------------------------------------------ helpers

    private DocumentRedactionService service(PdfRedactionPort engine) {
        return service(engine, new PdfBoxTextExtractionAdapter());
    }

    private DocumentRedactionService service(PdfRedactionPort engine, TextExtractionPort extraction) {
        TenantRedactionPolicyRepository policies = new TenantRedactionPolicyRepository() {
            @Override public Optional<TenantRedactionPolicy> findByTenantId(TenantId t) { return Optional.of(policy); }
            @Override public void save(TenantRedactionPolicy p) { }
        };
        return new DocumentRedactionService(documents, packages, artifacts, findings, storage, engine,
                new PdfBoxRedactionVerificationAdapter(), checksum, mock(DomainEventPublisher.class),
                new AuditService(audits::add), policies, extraction);
    }

    private static TesseractOcrAdapter ocr() {
        return new TesseractOcrAdapter(new TesseractOcrProperties());
    }

    private TenantRedactionPolicy policy(boolean searchable) {
        return new TenantRedactionPolicy(tenantId, java.util.Set.of(EntityType.SSN), 0.8, ReviewMode.UNCERTAIN_ONLY,
                true, Instant.now(), searchable);
    }

    /** Findings as the detection makes them: offsets in the extracted page text and the box from its spans. */
    private List<Finding> confirmedFindings() {
        TextExtractionPort.PageText page = new PdfBoxTextExtractionAdapter()
                .extract(new ByteArrayInputStream(source), "application/pdf").pages().get(0);
        List<Finding> out = new ArrayList<>();
        for (String value : List.of(SSN, CARD, DOB)) {
            out.add(finding(page, value, 0, EntityType.SSN, RedactionStyle.REMOVE_FULLY));
        }
        return out;
    }

    /** The same three places, each of its own kind and each asking for "keep the last 4". */
    private List<Finding> confirmedFindings(EntityType... kinds) {
        TextExtractionPort.PageText page = new PdfBoxTextExtractionAdapter()
                .extract(new ByteArrayInputStream(source), "application/pdf").pages().get(0);
        List<Finding> out = new ArrayList<>();
        List<String> values = List.of(SSN, CARD, DOB);
        for (int n = 0; n < values.size(); n++) {
            out.add(finding(page, values.get(n), 0, kinds[n], RedactionStyle.KEEP_LAST_FOUR));
        }
        return out;
    }

    private Finding finding(TextExtractionPort.PageText page, String value, int from, EntityType kind,
                            RedactionStyle style) {
        int start = page.text().indexOf(value, from);
        assertTrue(start >= 0);
        int end = start + value.length();
        double left = 1, top = 1, right = 0, bottom = 0;
        for (TextExtractionPort.TextSpan s : page.spans()) {
            if (s.endOffset() <= start || s.startOffset() >= end) continue;
            left = Math.min(left, s.left());
            top = Math.min(top, s.top());
            right = Math.max(right, s.left() + s.width());
            bottom = Math.max(bottom, s.top() + s.height());
        }
        FindingLocation location = FindingLocation.withBounds(1, start, end, FindingLocation.BoundingBox.of(
                Math.max(0, left - 0.004), Math.max(0, top - 0.004), right - left + 0.008, bottom - top + 0.008));
        return Finding.reconstitute(FindingId.generate(), tenantId, document.id(), kind, location,
                new Confidence(0.99), DetectorSource.of("test"), Instant.now(), FindingStatus.CONFIRMED,
                List.of(), Instant.now(), style);
    }

    private void assertNoValueAnywhere() {
        List<String> texts = new ArrayList<>();
        audits.forEach(a -> texts.add(a.detail()));
        logs.list.forEach(l -> texts.add(l.getFormattedMessage()));
        for (String text : texts) {
            if (text == null) continue;
            for (String value : List.of(SSN, CARD, DOB)) {
                String digits = value.replaceAll("[^0-9]", "");
                assertFalse(text.contains(value) || text.contains(digits), "an audit record or log line holds a value");
            }
        }
    }

    private static byte[] sourcePdf() throws Exception {
        return sourcePdf(false);
    }

    private static byte[] sourcePdf(List<String> lines) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(new PDRectangle(612, 792));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = 700;
                for (String line : lines) {
                    cs.beginText();
                    cs.setFont(FONT, 14);
                    cs.newLineAtOffset(72, y);
                    cs.showText(line);
                    cs.endText();
                    y -= 40;
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** {@code copyOnPage2}: a second page that prints the SSN again (test only: detection "missed" it). */
    private static byte[] sourcePdf(boolean copyOnPage2) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(new PDRectangle(612, 792));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = 700;
                for (String line : List.of("Loan application for a test borrower", "Borrower SSN: " + SSN,
                        "Card number: " + CARD, "Date of birth: " + DOB, "Monthly income is reviewed every year")) {
                    cs.beginText();
                    cs.setFont(FONT, 14);
                    cs.newLineAtOffset(72, y);
                    cs.showText(line);
                    cs.endText();
                    y -= 40;
                }
            }
            if (copyOnPage2) {
                PDPage second = new PDPage(new PDRectangle(612, 792));
                doc.addPage(second);
                try (PDPageContentStream cs = new PDPageContentStream(doc, second)) {
                    cs.beginText();
                    cs.setFont(FONT, 14);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("Summary page, applicant SSN " + SSN);
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** Test only: an engine whose text layer "kept" the SSN, as a broken OCR step might. */
    private record LeakingEngine(PdfRedactionPort real) implements PdfRedactionPort {
        @Override public RedactionOutput redact(InputStream originalPdf, List<RedactionTarget> targets) {
            return redact(originalPdf, targets, RedactionOptions.IMAGE_ONLY);
        }

        @Override public RedactionOutput redact(InputStream originalPdf, List<RedactionTarget> targets,
                                                RedactionOptions options) {
            RedactionOutput good = real.redact(originalPdf, targets, options);
            try (ReplayableContent content = good.redactedPdfContent(); InputStream in = content.openStream();
                 PDDocument doc = Loader.loadPDF(in.readAllBytes()); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                try (PDPageContentStream cs = new PDPageContentStream(doc, doc.getPage(0),
                        PDPageContentStream.AppendMode.APPEND, true)) {
                    cs.beginText();
                    cs.setRenderingMode(RenderingMode.NEITHER);
                    cs.setFont(FONT, 10);
                    cs.newLineAtOffset(72, 80);
                    cs.showText(SSN);
                    cs.endText();
                }
                doc.save(out);
                byte[] bytes = out.toByteArray();
                return new RedactionOutput(new Bytes(bytes), good.pagesRendered(), good.redactionsApplied());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private record Bytes(byte[] bytes) implements ReplayableContent {
        @Override public InputStream openStream() { return new ByteArrayInputStream(bytes); }
        @Override public long contentLength() { return bytes.length; }
        @Override public void close() { }
    }

    private RedactDocumentCommand command() {
        return RedactDocumentCommand.of(document.id(), pkg.id(), tenantId, CorrelationId.generate());
    }

    private DocumentPackage processingPackage() {
        DocumentPackage value = DocumentPackage.create(tenantId, PackageType.MANUAL_UPLOAD, ActorId.generate(),
                PackageMetadata.empty());
        Document placeholder = Document.create(tenantId, value.id(), FileName.of("a.pdf"), MimeType.pdf(), 64,
                Checksum.sha256("a"), DocumentType.PDF);
        value.addDocument(placeholder);
        value.submit();
        value.startProcessing();
        return value;
    }

    private Document redactingDocument() {
        return Document.reconstitute(DocumentId.generate(), tenantId, pkg.id(), FileName.of("a.pdf"), MimeType.pdf(),
                64, Checksum.sha256("a"), DocumentType.PDF, Instant.now(), DocumentStatus.REDACTING,
                List.of(Page.of(1, 612, 792)), Instant.now());
    }
}
