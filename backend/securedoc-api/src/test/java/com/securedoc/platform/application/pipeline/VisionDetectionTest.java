package com.securedoc.platform.application.pipeline;

import com.securedoc.platform.application.detection.command.DetectSensitiveDataCommand;
import com.securedoc.platform.application.detection.service.SensitiveDataDetectionService;
import com.securedoc.platform.application.extraction.command.ExtractDocumentTextCommand;
import com.securedoc.platform.application.extraction.service.TextExtractionService;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.ChecksumPort;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.FindingRepository;
import com.securedoc.platform.application.port.out.OcrPort;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.PageRouteRepository;
import com.securedoc.platform.application.port.out.ProcessingLockPort;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.application.port.out.TextExtractionPort;
import com.securedoc.platform.application.port.out.VisionModelPort;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.application.vision.VisionPageReader;
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
import com.securedoc.platform.domain.document.DocumentType;
import com.securedoc.platform.domain.document.PackageMetadata;
import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.finding.AiModel;
import com.securedoc.platform.domain.finding.DetectionPath;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.finding.FindingLocation.BoundingBox;
import com.securedoc.platform.domain.finding.FindingStatus;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.vision.PageRoute;
import com.securedoc.platform.domain.vision.VisionPageResult;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.FileName;
import com.securedoc.platform.domain.vo.MimeType;
import com.securedoc.platform.infrastructure.detection.LocalRuleBasedDetectionAdapter;
import com.securedoc.platform.infrastructure.extraction.HybridPdfTextExtractionAdapter;
import com.securedoc.platform.infrastructure.extraction.PdfBoxTextExtractionAdapter;
import com.securedoc.platform.infrastructure.extraction.PdfPageImageAdapter;
import com.securedoc.platform.infrastructure.ocr.TesseractOcrProperties;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 0064: a document whose pages are routed, read and searched by the real services on a real PDF (the OCR engine and
 * the vision model are stand-ins). Page 1 typed, 2 a handwritten mark, 3 a signature, 4 a scan OCR cannot read, 5 blank.
 */
class VisionDetectionTest {
    private static final AiModel MODEL = new AiModel("vl:8b", "vision-prompt-v2", "ab".repeat(32));
    private static byte[] pdf;

    private final TenantId tenantId = TenantId.generate();
    private VisionSettings settings = VisionSettings.DEFAULTS;
    private Set<EntityType> kinds = EnumSet.of(EntityType.SSN, EntityType.DATE_OF_BIRTH);
    private VisionModelPort model = (image, page) -> VisionPageResult.read(page, MODEL, List.of(
            new VisionPageResult.Item(page == 2 ? VisionPageResult.Kind.SSN : VisionPageResult.Kind.DATE_OF_BIRTH,
                    BoundingBox.of(0.5, 0.5, 0.2, 0.03))), false);

    @BeforeAll
    static void document() throws IOException {
        pdf = pdf();
    }

    @Test
    void eachPageTakesThePathItNeeds() {
        Run run = new Run();
        run.text.execute(run.extractCommand());
        assertEquals(List.of(PageRoute.Path.FAST, PageRoute.Path.VISION, PageRoute.Path.FAST_SIGNATURE,
                PageRoute.Path.VISION, PageRoute.Path.FAST), run.routeList().stream().map(PageRoute::path).toList());
        assertEquals(Set.of(PageRoute.Reason.HANDWRITING), run.routes.get(2).reasons());
        assertTrue(run.routes.get(4).reasons().containsAll(
                Set.of(PageRoute.Reason.NOT_READABLE, PageRoute.Reason.LOW_OCR_CONFIDENCE)));
        assertEquals(1, run.routes.get(3).signatures().size());
        assertEquals(List.of(2, 4), run.find.pagesForVision(run.detectCommand()));
    }

    @Test
    void withoutRoutingAPageOcrCannotReadStopsTheDocumentAsBefore() {
        Run run = new Run();
        assertThrows(RuntimeException.class, () -> run.extraction.extract(new ByteArrayInputStream(pdf),
                "application/pdf"));
        assertThrows(RuntimeException.class, () -> run.extraction.extractPages(new ByteArrayInputStream(pdf),
                "application/pdf", Set.of(4)));
        assertEquals(1, run.extraction.extractReadable(new ByteArrayInputStream(pdf), "application/pdf", Set.of(4))
                .pages().size());
    }

    @Test
    void jobsAndTheWholeDocumentGiveTheSameFindingsAndEveryFindingSaysWhereItCameFrom() {
        Run whole = new Run();
        whole.text.execute(whole.extractCommand());
        whole.find.execute(whole.detectCommand());
        Run jobs = new Run();
        jobs.text.execute(jobs.extractCommand());
        jobs.findAsJobs();
        assertEquals(places(whole.findings), places(jobs.findings));
        assertTrue(jobs.files.keySet().stream().filter(k -> k.contains("VISION_PAGE")).count() == 2);
        assertEquals(2, jobs.discarded.stream().filter(r -> r.key().contains("VISION_PAGE")).count(),
                "the vision results are deleted once gathered");

        Finding typed = only(whole, 1, EntityType.SSN);
        assertEquals(Set.of(DetectionPath.TEXT), typed.origin().paths());
        assertEquals(FindingStatus.CONFIRMED, typed.status());
        Finding handwritten = only(whole, 2, EntityType.SSN);
        assertEquals(Set.of(DetectionPath.VISION), handwritten.origin().paths());
        assertEquals(List.of(MODEL), handwritten.origin().models());
        assertEquals(FindingStatus.DETECTED, handwritten.status(), "a person sees a vision-only finding first");
        assertTrue(handwritten.location().hasBoundingBox());
        only(whole, 4, EntityType.DATE_OF_BIRTH);
        assertTrue(whole.findings.stream().noneMatch(f -> f.entityType() == EntityType.SIGNATURE), "off by default");

        String detail = whole.detectionDetail();
        assertTrue(detail.contains("textPages=3, signaturePages=1, visionPages=2, visionPagesToPerson=0"), detail);
        assertTrue(detail.contains("aiModels=[" + MODEL.encode() + "]"), detail);
        assertTrue(detail.contains("p2:HANDWRITING") && detail.contains("p4:"), detail);
        assertFalse(detail.contains("219-09"), "no value");
    }

    @Test
    void signaturesAreRedactedWhereTheEntityRedactsThem() {
        kinds = EnumSet.of(EntityType.SSN, EntityType.SIGNATURE);
        Run run = new Run();
        run.text.execute(run.extractCommand());
        run.find.execute(run.detectCommand());
        Finding signature = only(run, 3, EntityType.SIGNATURE);
        assertEquals(FindingStatus.CONFIRMED, signature.status(), "redacted without a person at the default threshold");
        assertEquals(Set.of(DetectionPath.SIGNATURE), signature.origin().paths());
        assertTrue(signature.origin().models().isEmpty());
    }

    @Test
    void whateverKeepsTheModelFromReadingAPageItGoesToAPersonAndNeverPassesUnread() {
        VisionModelPort[] models = {
                (image, page) -> VisionPageResult.notRead(page, "LV-AI-008", "MODEL_DIGEST_MISMATCH"),
                (image, page) -> VisionPageResult.notRead(page, "LV-AI-004", "MODEL_NOT_INSTALLED"),
                (image, page) -> { throw new IllegalStateException("test"); }};
        for (VisionModelPort failing : models) {
            model = failing;
            Run run = new Run();
            run.text.execute(run.extractCommand());
            run.findAsJobs();
            assertUnread(run);
        }
        settings = new VisionSettings(false, 0.85);
        model = (image, page) -> {
            throw new AssertionError("switched off: the model is never asked");
        };
        Run off = new Run();
        off.text.execute(off.extractCommand());
        assertEquals(List.of(), off.find.pagesForVision(off.detectCommand()));
        off.find.execute(off.detectCommand());
        assertUnread(off);
        assertTrue(off.detectionDetail().contains("p2:HANDWRITING->PERSON(VISION_OFF)"), off.detectionDetail());
    }

    private static void assertUnread(Run run) {
        for (int page : new int[]{2, 4}) {
            Finding unread = only(run, page, EntityType.UNREAD_PAGE);
            assertEquals(FindingStatus.DETECTED, unread.status());
            assertFalse(unread.location().hasBoundingBox());
        }
        assertTrue(run.detectionDetail().contains("visionPages=0, visionPagesToPerson=2"), run.detectionDetail());
        assertTrue(run.detectionDetail().contains("requiresReview=true"));
    }

    @Test
    void aDocumentReadBeforeThisTaskIsRoutedWhenItIsSearched() {
        Run run = new Run();
        run.text.execute(run.extractCommand());
        run.routes.clear();
        run.find.execute(run.detectCommand());
        assertEquals(5, run.routes.size());
        only(run, 2, EntityType.SSN);
        only(run, 4, EntityType.DATE_OF_BIRTH);
    }

    @Test
    void aPolicyChangedMeanwhileAsksTheModelNothingAgain() {
        Run run = new Run();
        run.text.execute(run.extractCommand());
        int[] asked = {0};
        VisionModelPort real = model;
        model = (image, page) -> {
            asked[0]++;
            return real.read(image, page);
        };
        run.findAsJobs(() -> kinds = EnumSet.of(EntityType.DATE_OF_BIRTH));
        assertEquals(2, asked[0]);
        assertTrue(run.findings.stream().allMatch(f -> f.entityType() == EntityType.DATE_OF_BIRTH));
        only(run, 4, EntityType.DATE_OF_BIRTH);
    }

    @Test
    void theRoutingThresholdIsTheEntitys() throws IOException {
        // A scan OCR reads at 0.80: below the default 0.85 it is hard, with the threshold at 0.70 it is not.
        byte[] scan = pdf;
        for (double threshold : new double[]{0.85, 0.70}) {
            TextExtractionPort reads = new HybridPdfTextExtractionAdapter(new PdfBoxTextExtractionAdapter(),
                    input -> ocr("Plain words on a page", 0.80), new TesseractOcrProperties());
            PageRoute route = reads.extractRouted(new ByteArrayInputStream(scan), "application/pdf", Set.of(4),
                    threshold).routes().get(0);
            assertEquals(threshold == 0.85, route.reasons().contains(PageRoute.Reason.LOW_OCR_CONFIDENCE));
            assertEquals(0.80, route.ocrConfidence(), 1e-9);
        }
    }

    // --------------------------------------------------------------------------------------------- helpers

    private static Finding only(Run run, int page, EntityType type) {
        List<Finding> found = run.findings.stream().filter(f -> f.location().pageNumber() == page
                && f.entityType() == type).toList();
        assertEquals(1, found.size(), type + " on page " + page + ": " + places(run.findings));
        return found.get(0);
    }

    private static List<String> places(List<Finding> findings) {
        return findings.stream().map(f -> f.entityType() + " " + f.location() + " " + f.confidence().value() + " "
                + f.detectorSource() + " " + f.status() + " " + f.origin()).sorted().toList();
    }

    private static OcrPort.OcrPageOutput ocr(String text, double confidence) {
        return new OcrPort.OcrPageOutput(text, text.isBlank() ? List.of()
                : List.of(new OcrPort.OcrTextSpan(0, text.length(), 0.1, 0.1, 0.3, 0.02, confidence)), confidence);
    }

    private final class Run {
        final Map<String, byte[]> files = new ConcurrentHashMap<>();
        final Map<UUID, Artifact> artifacts = new ConcurrentHashMap<>();
        final List<Finding> findings = new ArrayList<>();
        final List<ArtifactStorageReference> discarded = new ArrayList<>();
        final List<AuditRecord> audits = new ArrayList<>();
        final Map<Integer, PageRoute> routes = new TreeMap<>();
        final TextExtractionPort extraction = new HybridPdfTextExtractionAdapter(new PdfBoxTextExtractionAdapter(),
                input -> ocr("", 0), new TesseractOcrProperties());
        final DocumentPackage pkg = DocumentPackage.create(tenantId, PackageType.MANUAL_UPLOAD, ActorId.generate(),
                PackageMetadata.empty());
        final Document document = Document.create(tenantId, pkg.id(), FileName.of("a.pdf"), MimeType.pdf(), pdf.length,
                Checksum.sha256("a".repeat(64)), DocumentType.PDF);
        final TextExtractionService text;
        final SensitiveDataDetectionService find;

        Run() {
            pkg.addDocument(document);
            pkg.submit();
            pkg.startProcessing();
            Artifact original = Artifact.create(tenantId, document.id(), ArtifactType.ORIGINAL, MimeType.pdf(),
                    pdf.length, Checksum.sha256("o".repeat(64)));
            original.markAvailable(ArtifactStorageReference.local("original.pdf"));
            artifacts.put(original.id().value(), original);
            files.put("original.pdf", pdf);
            DocumentRepository documents = mock(DocumentRepository.class);
            when(documents.findByIdAndTenantId(any(), any())).thenReturn(Optional.of(document));
            PackageRepository packages = mock(PackageRepository.class);
            when(packages.findByIdAndTenantId(any(), any())).thenReturn(Optional.of(pkg));
            ArtifactRepository artifactStore = mock(ArtifactRepository.class);
            when(artifactStore.findByDocumentIdAndTenantId(any(), any()))
                    .thenAnswer(i -> new ArrayList<>(artifacts.values()));
            org.mockito.Mockito.doAnswer(i -> {
                Artifact a = i.getArgument(0);
                artifacts.put(a.id().value(), a);
                return null;
            }).when(artifactStore).save(any());
            StoragePort storage = new StoragePort() {
                @Override public ArtifactStorageReference store(TenantId tenant, String path, InputStream content,
                                                                long sizeBytes, String mimeType) {
                    try {
                        files.put(path, content.readAllBytes());
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                    return ArtifactStorageReference.local(path);
                }
                @Override public InputStream retrieve(ArtifactStorageReference reference) {
                    return new ByteArrayInputStream(files.get(reference.key()));
                }
                @Override public void delete(ArtifactStorageReference reference) {
                    files.remove(reference.key());
                }
            };
            ChecksumPort checksums = content -> {
                try {
                    return Checksum.sha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(content.readAllBytes())));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            };
            FindingRepository findingStore = mock(FindingRepository.class);
            org.mockito.Mockito.doAnswer(i -> {
                findings.addAll(i.getArgument(0));
                return null;
            }).when(findingStore).saveAll(any());
            TenantRedactionPolicyRepository policies = mock(TenantRedactionPolicyRepository.class);
            when(policies.findByTenantId(any())).thenAnswer(i -> Optional.of(new TenantRedactionPolicy(tenantId, kinds,
                    0.80, ReviewMode.UNCERTAIN_ONLY, true, Instant.now(), true, PageBatchSettings.DEFAULTS, settings)));
            PageRouteRepository routeStore = new PageRouteRepository() {
                @Override public void save(TenantId tenant, DocumentId doc, List<PageRoute> saved) {
                    saved.forEach(r -> routes.put(r.page(), r));
                }
                @Override public List<PageRoute> findByDocument(TenantId tenant, DocumentId doc) {
                    return routeList();
                }
            };
            AuditService audit = new AuditService(audits::add);
            LocalRuleBasedDetectionAdapter rules = new LocalRuleBasedDetectionAdapter();
            SensitiveContentAnalysisPort ai = request -> new SensitiveContentAnalysisPort.AnalysisOutput(List.of());
            text = new TextExtractionService(documents, packages, artifactStore, storage, extraction, checksums,
                    mock(ProcessingLockPort.class), event -> { }, audit,
                    new TextExtractionService.PageRouting(routeStore, policies));
            find = new SensitiveDataDetectionService(documents, packages, artifactStore, storage, ai, rules, rules,
                    policies, extraction, findingStore, event -> { }, audit, checksums,
                    new SensitiveDataDetectionService.Vision(routeStore, new VisionPageReader(artifactStore, storage,
                            new PdfPageImageAdapter(), (image, page) -> model.read(image, page))));
        }

        List<PageRoute> routeList() {
            return new ArrayList<>(routes.values());
        }

        ExtractDocumentTextCommand extractCommand() {
            document.startExtraction();
            return ExtractDocumentTextCommand.of(document.id(), pkg.id(), tenantId, "k", "t", CorrelationId.generate());
        }

        DetectSensitiveDataCommand detectCommand() {
            return DetectSensitiveDataCommand.of(document.id(), pkg.id(), tenantId, CorrelationId.generate());
        }

        void findAsJobs() {
            findAsJobs(() -> { });
        }

        void findAsJobs(Runnable beforeGathering) {
            PageBatch search = new PageBatch(UUID.randomUUID(), 1, 5);
            List<PageBatch> vision = new ArrayList<>();
            for (int page : find.pagesForVision(detectCommand())) {
                PageBatch one = new PageBatch(UUID.randomUUID(), page, page);
                vision.add(one);
                find.saveVisionPage(command(one), find.readVisionPage(detectCommand(), page));
            }
            find.detectBatch(command(search));
            assertTrue(findings.isEmpty(), "nothing is a finding before the document is gathered");
            beforeGathering.run();
            StagedStepScope.tracking(new ArrayList<>(), discarded, () ->
                    find.completeFromBatches(detectCommand(), List.of(search), vision));
        }

        PageBatchCommand command(PageBatch batch) {
            return new PageBatchCommand(batch, document.id(), pkg.id(), tenantId, CorrelationId.generate());
        }

        String detectionDetail() {
            return audits.stream().filter(a -> a.action() == AuditAction.DOCUMENT_DETECTION_COMPLETED).findFirst()
                    .orElseThrow().detail();
        }
    }

    private static byte[] pdf() throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int n = 1; n <= 3; n++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    for (int line = 0; line < 12; line++) {
                        line(cs, font, 60, 740 - 18 * line, "Synthetic typed line number " + line + " of a loan file page");
                    }
                    if (n == 1) line(cs, font, 60, 400, "Borrower SSN: 219-09-1234");
                    if (n == 3) line(cs, font, 60, 203, "Signature:");
                    if (n > 1) scribble(cs, n == 2 ? 300 : 160);
                }
            }
            BufferedImage scan = new BufferedImage(850, 1100, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scan.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 850, 1100);
            g.setColor(Color.DARK_GRAY);
            for (int y = 100; y < 900; y += 30) g.fillRect(90, y, 500, 6);
            g.dispose();
            PDPage scanned = new PDPage();
            doc.addPage(scanned);
            try (PDPageContentStream cs = new PDPageContentStream(doc, scanned)) {
                cs.drawImage(LosslessFactory.createFromImage(doc, scan), 0, 0, 612, 792);
            }
            doc.addPage(new PDPage());
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static void scribble(PDPageContentStream cs, float x) throws IOException {
        cs.setLineWidth(1.6f);
        cs.moveTo(x, 200);
        float[] ys = {215, 196, 218, 199, 212, 197, 216, 203};
        for (int i = 0; i < ys.length; i++) cs.lineTo(x + 9 * (i + 1), ys[i]);
        cs.stroke();
    }

    private static void line(PDPageContentStream cs, PDType1Font font, float x, float y, String text)
            throws IOException {
        cs.beginText();
        cs.setFont(font, 11);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }
}
