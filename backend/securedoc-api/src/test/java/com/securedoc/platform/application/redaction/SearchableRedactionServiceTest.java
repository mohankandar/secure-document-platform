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

    // ------------------------------------------------------------------ helpers

    private DocumentRedactionService service(PdfRedactionPort engine) {
        TenantRedactionPolicyRepository policies = new TenantRedactionPolicyRepository() {
            @Override public Optional<TenantRedactionPolicy> findByTenantId(TenantId t) { return Optional.of(policy); }
            @Override public void save(TenantRedactionPolicy p) { }
        };
        return new DocumentRedactionService(documents, packages, artifacts, findings, storage, engine,
                new PdfBoxRedactionVerificationAdapter(), checksum, mock(DomainEventPublisher.class),
                new AuditService(audits::add), policies, new PdfBoxTextExtractionAdapter());
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
            int start = page.text().indexOf(value);
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
            out.add(Finding.reconstitute(FindingId.generate(), tenantId, document.id(), EntityType.SSN, location,
                    new Confidence(0.99), DetectorSource.of("test"), Instant.now(), FindingStatus.CONFIRMED,
                    List.of(), Instant.now()));
        }
        return out;
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
