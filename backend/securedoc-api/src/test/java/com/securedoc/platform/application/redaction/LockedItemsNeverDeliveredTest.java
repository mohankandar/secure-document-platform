package com.securedoc.platform.application.redaction;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.securedoc.platform.application.detection.service.FindingLocationMapper;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.ChecksumPort;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.DomainEventPublisher;
import com.securedoc.platform.application.port.out.FindingRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.RedactionVerificationPort.RedactedValues;
import com.securedoc.platform.application.port.out.SensitiveDataDetectionPort;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.application.port.out.TextExtractionPort;
import com.securedoc.platform.application.redaction.command.RedactDocumentCommand;
import com.securedoc.platform.application.redaction.service.DocumentRedactionService;
import com.securedoc.platform.application.secondcheck.FinalPatternCheck;
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
import com.securedoc.platform.infrastructure.detection.LocalRuleBasedDetectionAdapter;
import com.securedoc.platform.infrastructure.extraction.HybridPdfTextExtractionAdapter;
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
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 0099: the owner's test of 2026-11-25 (findings R1 to R3) and the guard before delivery. The owner's synthetic
 * files go through the product's own steps (text reading with OCR, the pattern rules, the default policy of a new
 * entity, the redaction, its verification and the final check) and every delivered file is read again: no locked
 * value and no enabled value is in its text or in OCR of it. The guard holds a file for a person when a value was
 * missed, and lets it go once a person checked the page or kept the value. Synthetic values only; none is logged.
 */
class LockedItemsNeverDeliveredTest {

    private static final String CARD = "4111 1111 1111 1111";
    /** The locked and enabled values of each file of the owner's test (README.md there). */
    private static final Map<String, List<String>> VALUES = Map.of(
            "01-loan-application-text.pdf", List.of("512-34-8876", "287-65-4321", "03/14/1979", "11/02/1981", CARD),
            "02-w2-wage-statement-text.pdf", List.of("512-34-8876"),
            "03-bank-statement-scanned.pdf", List.of(CARD),
            // The note on the page (an SSN and the card) goes with every annotation; the verification refuses any left.
            "09-form-fields-and-comments.pdf", List.of("512-34-8876", "03/14/1979"),
            "13-hidden-text-layer.pdf", List.of("512-34-8876", "03/14/1979", "4111111111111111"));
    /** Files with nothing locked or enabled on them by default: delivered, never held. */
    private static final List<String> CLEAN = List.of("07-court-filing-party-names.pdf",
            "11-tax-ids-and-passport-text.pdf", "12-clean-no-sensitive-data.pdf");
    private static final PDType1Font FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

    private final TenantId tenantId = TenantId.generate();
    private final DocumentPackage pkg = processingPackage();
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final PackageRepository packages = mock(PackageRepository.class);
    private final ArtifactRepository artifacts = mock(ArtifactRepository.class);
    private final FindingRepository findings = mock(FindingRepository.class);
    private final StoragePort storage = mock(StoragePort.class);
    private final ChecksumPort checksum = mock(ChecksumPort.class);
    private final List<AuditRecord> audits = new ArrayList<>();
    private final List<byte[]> stored = new ArrayList<>();
    private final List<Finding> saved = new ArrayList<>();
    private final TextExtractionPort reading = new HybridPdfTextExtractionAdapter(new PdfBoxTextExtractionAdapter(),
            new TesseractOcrAdapter(new TesseractOcrProperties()), new TesseractOcrProperties());
    private final TenantRedactionPolicy policy = TenantRedactionPolicy.defaults(tenantId);
    private Document document;
    private byte[] source;
    private List<Finding> current = List.of();
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
        when(packages.findByIdAndTenantId(pkg.id(), tenantId)).thenReturn(Optional.of(pkg));
        when(storage.retrieve(any())).thenAnswer(i -> new ByteArrayInputStream(source));
        when(storage.storeForPackage(any(), any(), anyString(), any(), anyLong(), anyString())).thenAnswer(i -> {
            stored.add(((InputStream) i.getArgument(3)).readAllBytes());
            return ArtifactStorageReference.local("t/p/d/REDACTED/r");
        });
        when(checksum.compute(any())).thenReturn(Checksum.sha256("redacted"));
        when(findings.findByDocumentIdAndTenantId(any(), any())).thenAnswer(i -> current);
        doAnswer(i -> {
            i.<Iterable<Finding>>getArgument(0).forEach(saved::add);
            return null;
        }).when(findings).saveAll(any());
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(logs);
        root.setLevel(rootLevelBefore);
    }

    // ------------------------------------------------------------------------------------------- the owner's files

    @Test
    void r1TheCardAndItsCodeOnATextPageAreRedacted() throws Exception {
        byte[] output = deliver("01-loan-application-text.pdf");
        String text = everyReading(output);
        assertFalse(text.matches("(?s).*CVV\\s*1\\s*2\\s*3.*"), "the card security code is not in the delivered file");
        assertFalse(text.contains("742"), "the credit score is not in the delivered file");
        assertTrue(text.contains("Experian"), "the rest of the line is still there");
    }

    @Test
    void r2TheCardOnAScannedTiltedPageIsRedacted() throws Exception {
        deliver("03-bank-statement-scanned.pdf");
    }

    @Test
    void r3WhiteAndOnePointTextIsSearchedAndRemoved() throws Exception {
        byte[] output = deliver("13-hidden-text-layer.pdf");
        assertTrue(everyReading(output).contains("appraised value"), "the visible text is still there");
    }

    @Test
    void theOtherFilesWithValuesAreDeliveredWithoutThem() throws Exception {
        deliver("02-w2-wage-statement-text.pdf");
        deliver("09-form-fields-and-comments.pdf");
    }

    @Test
    void filesWithNothingToRedactAreDeliveredAndNotHeld() throws Exception {
        for (String file : CLEAN) {
            byte[] output = deliver(file);
            assertNotNull(output, file);
        }
    }

    // ------------------------------------------------------------------------------------------------- the guard

    @Test
    void aMissedCardHoldsTheFileForAPersonWithTheKindOnly() throws Exception {
        source = pdf(List.of("Borrower SSN: 412-55-7531", "Paid by card 4000 0566 5566 5556 on 10/28"));
        TextExtractionPort.PageText page = reading.extractReadable(new ByteArrayInputStream(source),
                "application/pdf", null).pages().get(0);
        document = redactingDocument(1);
        // Detection "missed" the card: only the SSN is a finding.
        current = List.of(confirmed(page, "412-55-7531", EntityType.SSN));

        service().execute(command());

        assertTrue(stored.isEmpty(), "nothing is kept, so nothing can be delivered");
        assertEquals(DocumentStatus.REDACTING, document.status());
        assertEquals(1, saved.size());
        Finding held = saved.get(0);
        assertEquals(EntityType.UNREAD_PAGE, held.entityType());
        assertEquals(FindingStatus.DETECTED, held.status(), "it waits for a person");
        assertEquals(1, held.location().pageNumber());
        assertEquals(List.of("CARD"), FinalPatternCheck.kindsOf(held));
        AuditRecord check = audits.stream().filter(a -> a.action() == AuditAction.DOCUMENT_FINAL_CHECK).findFirst()
                .orElseThrow();
        assertTrue(check.detail().contains("pagesToCheck=1") && check.detail().contains("p1:CARD"), check.detail());
        assertNoValueInAuditOrLogs(List.of("412-55-7531", "4000 0566 5566 5556"));
    }

    @Test
    void aPageAPersonCheckedOrAValueAPersonKeptIsNotHeldAgain() throws Exception {
        source = pdf(List.of("Borrower SSN: 412-55-7531", "Paid by card 4000 0566 5566 5556 on 10/28"));
        TextExtractionPort.PageText page = reading.extractReadable(new ByteArrayInputStream(source),
                "application/pdf", null).pages().get(0);
        document = redactingDocument(1);
        Finding checked = Finding.reconstitute(FindingId.generate(), tenantId, document.id(), EntityType.UNREAD_PAGE,
                FindingLocation.unlocated(1), new Confidence(0.0), new DetectorSource(FinalPatternCheck.SOURCE, "CARD"),
                Instant.now(), FindingStatus.DISMISSED, List.of(), Instant.now(), RedactionStyle.REMOVE_FULLY);
        current = List.of(confirmed(page, "412-55-7531", EntityType.SSN), checked);
        service().execute(command());
        assertEquals(1, stored.size(), "a page a person checked is delivered");

        stored.clear();
        document = redactingDocument(1);
        Finding kept = dismissed(page, "4000 0566 5566 5556", EntityType.CREDIT_CARD_NUMBER);
        current = List.of(confirmed(page, "412-55-7531", EntityType.SSN), kept);
        service().execute(command());
        assertEquals(1, stored.size(), "a value a person decided to keep visible is delivered");
    }

    @Test
    void anEntityFromBeforeTheCatalogStillHasItsLockedItemsSearched() {
        TenantRedactionPolicy old = new TenantRedactionPolicy(tenantId, Set.of(EntityType.SSN), 0.8,
                ReviewMode.UNCERTAIN_ONLY, true, Instant.now(), true, com.securedoc.platform.domain.business
                .PageBatchSettings.DEFAULTS, Set.of(), Set.of(), false);
        assertTrue(old.enabledEntityTypes().containsAll(Set.of(EntityType.CREDIT_CARD_NUMBER, EntityType.CVV,
                EntityType.PIN)));
    }

    // ------------------------------------------------------------------------------------------------- helpers

    /**
     * The file through the product's steps; the delivered bytes, checked against the file's values. A file the final
     * check holds is never delivered: it fails the test, as the owner's files must all be found by detection itself.
     */
    private byte[] deliver(String file) throws Exception {
        stored.clear();
        saved.clear();
        try (InputStream in = getClass().getResourceAsStream("/owner-test-2026-11-25/" + file)) {
            source = in.readAllBytes();
        }
        List<TextExtractionPort.PageText> pages = reading.extractReadable(new ByteArrayInputStream(source),
                "application/pdf", null).pages();
        document = redactingDocument(pages.size());
        List<SensitiveDataDetectionPort.PageInput> inputs = pages.stream()
                .map(p -> new SensitiveDataDetectionPort.PageInput(p.pageNumber(), p.text())).toList();
        List<Finding> found = new ArrayList<>();
        for (SensitiveDataDetectionPort.DetectedEntity e : new LocalRuleBasedDetectionAdapter().detect(inputs).entities()) {
            EntityType type = EntityType.valueOf(e.entityType());
            if (!policy.enabledEntityTypes().contains(type)) continue;
            TextExtractionPort.PageText page = pages.get(e.pageNumber() - 1);
            FindingLocation.BoundingBox box = FindingLocationMapper.resolveBoundingBox(page, e.startOffset(),
                    e.endOffset()).orElseThrow(() -> new AssertionError(file + ": a finding without a place"));
            found.add(Finding.reconstitute(FindingId.generate(), tenantId, document.id(), type,
                    FindingLocation.withBounds(e.pageNumber(), e.startOffset(), e.endOffset(), pad(box)),
                    new Confidence(0.99), DetectorSource.of("test"), Instant.now(), FindingStatus.CONFIRMED,
                    List.of(), Instant.now(), RedactionStyle.REMOVE_FULLY));
        }
        current = found;

        service().execute(command());

        assertTrue(saved.stream().noneMatch(f -> f.entityType() == EntityType.UNREAD_PAGE),
                file + ": held by the final check, so detection missed something");
        assertEquals(1, stored.size(), file + ": delivered");
        byte[] output = stored.get(0);
        String text = everyReading(output);
        RedactedValues values = RedactedValues.of(VALUES.getOrDefault(file, List.of()));
        assertFalse(values.appearsIn(text), file + ": a locked or enabled value is in the delivered file");
        assertNoValueInAuditOrLogs(VALUES.getOrDefault(file, List.of()));
        return output;
    }

    /** The delivered file as a reader gets it: its text layer, and the product's reading with OCR. */
    private String everyReading(byte[] output) throws Exception {
        StringBuilder all = new StringBuilder();
        try (PDDocument out = Loader.loadPDF(output)) {
            all.append(new PDFTextStripper().getText(out)).append('\n');
        }
        for (TextExtractionPort.PageText page : reading.extractReadable(new ByteArrayInputStream(output),
                "application/pdf", null).pages()) {
            all.append(page.text()).append('\n');
        }
        return all.toString();
    }

    private DocumentRedactionService service() {
        when(documents.findByIdAndTenantId(document.id(), tenantId)).thenReturn(Optional.of(document));
        Artifact original = Artifact.create(tenantId, document.id(), ArtifactType.ORIGINAL, MimeType.pdf(),
                source.length, Checksum.sha256("original"));
        original.markAvailable(ArtifactStorageReference.local("t/p/d/ORIGINAL/a"));
        when(artifacts.findByDocumentIdAndTenantId(document.id(), tenantId)).thenReturn(List.of(original));
        TenantRedactionPolicyRepository policies = new TenantRedactionPolicyRepository() {
            @Override public Optional<TenantRedactionPolicy> findByTenantId(TenantId t) { return Optional.of(policy); }
            @Override public void save(TenantRedactionPolicy p) { }
        };
        return new DocumentRedactionService(documents, packages, artifacts, findings, storage,
                new PdfBoxBurnFlatRedactionAdapter(new TesseractOcrAdapter(new TesseractOcrProperties())),
                new PdfBoxRedactionVerificationAdapter(), checksum, mock(DomainEventPublisher.class),
                new AuditService(audits::add), policies, reading)
                .withFinalCheck(new FinalPatternCheck(reading, new LocalRuleBasedDetectionAdapter()));
    }

    private Finding confirmed(TextExtractionPort.PageText page, String value, EntityType kind) {
        return finding(page, value, kind, FindingStatus.CONFIRMED);
    }

    private Finding dismissed(TextExtractionPort.PageText page, String value, EntityType kind) {
        return finding(page, value, kind, FindingStatus.DISMISSED);
    }

    private Finding finding(TextExtractionPort.PageText page, String value, EntityType kind, FindingStatus status) {
        int start = page.text().indexOf(value);
        assertTrue(start >= 0);
        int end = start + value.length();
        FindingLocation.BoundingBox box = FindingLocationMapper.resolveBoundingBox(page, start, end).orElseThrow();
        return Finding.reconstitute(FindingId.generate(), tenantId, document.id(), kind,
                FindingLocation.withBounds(page.pageNumber(), start, end, pad(box)), new Confidence(0.99),
                DetectorSource.of("test"), Instant.now(), status, List.of(), Instant.now(), RedactionStyle.REMOVE_FULLY);
    }

    private static FindingLocation.BoundingBox pad(FindingLocation.BoundingBox b) {
        double left = Math.max(0, b.left() - 0.004);
        double top = Math.max(0, b.top() - 0.004);
        return FindingLocation.BoundingBox.of(left, top, Math.min(1 - left, b.width() + 0.008),
                Math.min(1 - top, b.height() + 0.008));
    }

    private void assertNoValueInAuditOrLogs(List<String> values) {
        List<String> texts = new ArrayList<>();
        audits.forEach(a -> texts.add(a.detail()));
        logs.list.forEach(l -> texts.add(l.getFormattedMessage()));
        for (String text : texts) {
            if (text == null) continue;
            for (String value : values) {
                assertFalse(text.contains(value) || text.contains(value.replaceAll("[^0-9]", "")),
                        "an audit record or log line holds a value");
            }
        }
    }

    private static byte[] pdf(List<String> lines) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(new PDRectangle(612, 792));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setNonStrokingColor(Color.BLACK);
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

    private Document redactingDocument(int pages) {
        List<Page> all = new ArrayList<>();
        for (int n = 1; n <= pages; n++) all.add(Page.of(n, 612, 792));
        return Document.reconstitute(DocumentId.generate(), tenantId, pkg.id(), FileName.of("a.pdf"), MimeType.pdf(),
                64, Checksum.sha256("a"), DocumentType.PDF, Instant.now(), DocumentStatus.REDACTING, all,
                Instant.now());
    }
}
