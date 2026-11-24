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
import com.securedoc.platform.application.port.out.ProcessingLockPort;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.application.port.out.TextExtractionPort;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactStatus;
import com.securedoc.platform.domain.artifact.ArtifactStorageReference;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.business.PageBatchSettings;
import com.securedoc.platform.domain.business.ReviewMode;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.document.DocumentType;
import com.securedoc.platform.domain.document.PackageMetadata;
import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.FileName;
import com.securedoc.platform.domain.vo.MimeType;
import com.securedoc.platform.infrastructure.detection.LocalRuleBasedDetectionAdapter;
import com.securedoc.platform.infrastructure.extraction.HybridPdfTextExtractionAdapter;
import com.securedoc.platform.infrastructure.extraction.PdfBoxTextExtractionAdapter;
import com.securedoc.platform.infrastructure.ocr.TesseractOcrProperties;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 0049, step 1: a 500-page document read and searched in page batches gives exactly the text, the findings and the
 * boxes of the whole-document run. Real PDF, real PDF text reading, real Text and Find services, real rules; the AI is
 * a stand-in that also finds names, some only with the page before in the same request. Synthetic values only.
 */
class PageBatchesEqualWholeDocumentTest {
    private static final int PAGES = 500;
    private static final PageBatchSettings SETTINGS = PageBatchSettings.DEFAULTS;
    private static final Pattern DOB = Pattern.compile("Applicant (\\d\\d/\\d\\d/\\d{4})");
    private static byte[] pdf;

    private final TenantId tenantId = TenantId.generate();
    private final List<AuditRecord> audits = Collections.synchronizedList(new ArrayList<>());
    private final List<Object> events = Collections.synchronizedList(new ArrayList<>());
    private SensitiveContentAnalysisPort ai = PageBatchesEqualWholeDocumentTest::standInAi;
    private TenantRedactionPolicy policy = policy(EnumSet.of(EntityType.SSN, EntityType.DATE_OF_BIRTH));

    @BeforeAll
    static void document() throws IOException {
        pdf = fiveHundredPages();
    }

    @Test
    void theDefaultPlanCoversEveryPageWithTwoSharedPages() {
        List<PageBatchPlan.Range> text = PageBatchPlan.text(PAGES, SETTINGS);
        assertEquals(25, text.size());
        assertEquals(new PageBatchPlan.Range(1, 20), text.get(0));
        assertEquals(new PageBatchPlan.Range(21, 40), text.get(1));
        List<PageBatchPlan.Range> find = PageBatchPlan.find(PAGES, SETTINGS);
        assertEquals(new PageBatchPlan.Range(1, 20), find.get(0));
        assertEquals(new PageBatchPlan.Range(19, 38), find.get(1));
        assertEquals(PAGES, find.get(find.size() - 1).to());
        assertEquals(28, find.size());
        assertEquals(List.of(), PageBatchPlan.text(20, SETTINGS), "a document up to the batch size is one job");
        assertEquals(List.of(), PageBatchPlan.find(20, SETTINGS));
        assertEquals(List.of(new PageBatchPlan.Range(1, 20), new PageBatchPlan.Range(19, 21)),
                PageBatchPlan.find(21, SETTINGS));
    }

    @Test
    void batchesGiveTheTextFindingsAndBoxesOfTheWholeDocument() {
        Run whole = new Run();
        whole.text.execute(whole.extractCommand());
        whole.find.execute(whole.detectCommand());

        Run batched = new Run();
        batched.readInBatches();
        assertEquals(PAGES, batched.document.pageCount());
        assertEquals(whole.storedText(), batched.storedText(), "the stored text is byte for byte the same");
        batched.findInBatches();

        assertEquals(DocumentStatus.REDACTING, batched.document.status());
        assertFalse(whole.findings.isEmpty());
        assertEquals(places(whole.findings), places(batched.findings), "same kinds, places, boxes, confidence, status");
        assertEquals(batched.findings.size(), Set.copyOf(places(batched.findings)).size(), "no finding is doubled");
        // Both shared pages of the first edge (19, 20) and the pages around it have their findings, once.
        for (int page : new int[]{18, 19, 20, 21, 37, 38, 39}) {
            assertEquals(1, batched.findings.stream().filter(f -> f.location().pageNumber() == page
                    && f.entityType() == EntityType.SSN).count(), "SSN on page " + page);
        }
        // A date of birth on page 21 is known only together with page 20 (a label at the foot of the page before).
        assertEquals(1, batched.findings.stream().filter(f -> f.location().pageNumber() == 21
                && f.entityType() == EntityType.DATE_OF_BIRTH).count(), "a finding across a page break is kept");
        Run noOverlap = new Run();
        noOverlap.readInBatches();
        noOverlap.settings = new PageBatchSettings(20, 0);
        noOverlap.find.completeFromBatches(noOverlap.detectCommand(), noOverlap.searchBatches());
        assertEquals(0, noOverlap.findings.stream().filter(f -> f.entityType() == EntityType.DATE_OF_BIRTH).count(),
                "without shared pages it would be missed: the overlap is what keeps it");
        assertEquals(whole.findings.stream().filter(f -> f.entityType() == EntityType.DATE_OF_BIRTH).count(),
                batched.findings.stream().filter(f -> f.entityType() == EntityType.DATE_OF_BIRTH).count());

        // Once per document, with the same counts.
        for (AuditAction once : List.of(AuditAction.DOCUMENT_EXTRACTION_COMPLETED,
                AuditAction.DOCUMENT_DETECTION_COMPLETED)) {
            List<String> details = audits.stream().filter(a -> a.action() == once).map(AuditRecord::detail).toList();
            assertEquals(3, details.size(), "one per run (whole, batches, batches without overlap): " + once);
            assertEquals(details.get(0), details.get(1));
        }
        // The batch results are gone: records deleted (their files follow once the step is saved).
        assertTrue(batched.artifacts.values().stream().filter(a -> a.artifactType() == ArtifactType.FINDINGS_BATCH
                || a.artifactType() == ArtifactType.EXTRACTED_TEXT_BATCH)
                .allMatch(a -> a.status() == ArtifactStatus.DELETED));
        assertEquals(25 + 28, batched.discarded.size());
        // What a Find batch keeps is places only: no value is in it.
        for (byte[] file : batched.findingFiles()) {
            assertFalse(new String(file, java.nio.charset.StandardCharsets.ISO_8859_1).contains("219-09-"));
        }
    }

    /**
     * Task 0052: the real one-page-per-request adapter against a local stand-in server that counts the pages it gets.
     * Without shared pages no page is sent twice, and the findings are those of the whole-document run.
     */
    @Test
    void withOnePagePerAiRequestNoPageIsSentTwiceAndTheFindingsAreTheSame() throws Exception {
        Map<Integer, java.util.concurrent.atomic.AtomicInteger> sent = new ConcurrentHashMap<>();
        Pattern pageOf = Pattern.compile("Synthetic loan file, page (\\d+) of");
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        server.setExecutor(pool);
        server.createContext("/api/chat", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            Matcher pages = pageOf.matcher(body);
            while (pages.find()) sent.computeIfAbsent(Integer.parseInt(pages.group(1)),
                    n -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
            Matcher date = DOB.matcher(body);
            String finding = date.find() ? "{\\\"entityType\\\":\\\"DATE_OF_BIRTH\\\",\\\"matchedText\\\":\\\""
                    + date.group(1) + "\\\",\\\"contextText\\\":\\\"\\\",\\\"confidence\\\":0.95}" : "";
            byte[] answer = ("{\"message\":{\"content\":\"{\\\"findings\\\":[" + finding + "]}\"}}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
        try {
            ai = new com.securedoc.platform.ai.analysis.OllamaSensitiveContentAnalysisAdapter(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-model", java.time.Duration.ofSeconds(20),
                    2, 1, java.time.Duration.ZERO);
            assertFalse(ai.mayCoverSeveralPages(tenantId), "the default is one page per request");
            Run whole = new Run();
            whole.text.execute(whole.extractCommand());
            whole.find.execute(whole.detectCommand());
            assertEquals(PAGES, requests(sent));

            Run shared = new Run();
            shared.readInBatches();
            List<PageBatch> sharedBatches = shared.searchBatches();
            int withOverlap = requests(sent);
            shared.find.completeFromBatches(shared.detectCommand(), sharedBatches);

            Run apart = new Run();
            apart.readInBatches();
            apart.settings = new com.securedoc.platform.application.processing.service.StagedProcessingService.PageBatching(
                    t -> SETTINGS, d -> java.util.OptionalInt.empty(), ai::mayCoverSeveralPages).findSettings(tenantId);
            assertEquals(new PageBatchSettings(20, 0), apart.settings);
            List<PageBatch> batches = apart.searchBatches();
            assertEquals(PAGES, sent.size(), "every page is sent");
            assertTrue(sent.values().stream().allMatch(n -> n.get() == 1), "no page is sent to the AI twice");
            int withoutOverlap = requests(sent);
            apart.find.completeFromBatches(apart.detectCommand(), batches);

            assertEquals(0, requests(sent), "gathering asks the AI nothing");
            assertEquals(28, sharedBatches.size());
            assertEquals(25, batches.size());
            assertEquals(554, withOverlap);
            assertEquals(500, withoutOverlap);
            assertFalse(whole.findings.isEmpty());
            assertTrue(whole.findings.stream().anyMatch(f -> f.entityType() == EntityType.DATE_OF_BIRTH));
            assertEquals(places(whole.findings), places(apart.findings), "the findings of the whole-document run");
            assertEquals(places(shared.findings), places(apart.findings), "and those of the run with shared pages");
            System.out.println("TASK_0052 pages=500 overlap2: batches=" + sharedBatches.size() + " aiPageRequests="
                    + withOverlap + "; overlap0: batches=" + batches.size() + " aiPageRequests=" + withoutOverlap);
        } finally {
            server.stop(0);
            pool.shutdownNow();
        }
    }

    /** The requests since the last call. */
    private static int requests(Map<Integer, java.util.concurrent.atomic.AtomicInteger> sent) {
        int total = sent.values().stream().mapToInt(java.util.concurrent.atomic.AtomicInteger::get).sum();
        sent.clear();
        return total;
    }

    @Test
    void aMissingOrChangedBatchResultRefusesTheDocument() {
        Run missing = new Run();
        List<PageBatch> read = missing.readBatches();
        missing.artifacts.values().stream().filter(a -> a.artifactType() == ArtifactType.EXTRACTED_TEXT_BATCH)
                .findFirst().ifPresent(Artifact::delete);
        missing.document.startExtraction();
        assertThrows(IllegalStateException.class,
                () -> missing.text.completeFromBatches(missing.extractCommand(), read));
        assertEquals(DocumentStatus.FAILED, missing.document.status());

        Run gap = new Run();
        List<PageBatch> all = gap.readBatches();
        gap.document.startExtraction();
        assertThrows(IllegalStateException.class, () -> gap.text.completeFromBatches(gap.extractCommand(),
                java.util.stream.Stream.concat(all.stream().limit(3), all.stream().skip(4)).toList()));

        Run changed = new Run();
        List<PageBatch> batches = changed.readBatches();
        changed.files.replaceAll((key, bytes) -> {
            if (!key.contains("EXTRACTED_TEXT_BATCH")) return bytes;
            byte[] other = bytes.clone();
            other[other.length - 1]++;
            return other;
        });
        changed.document.startExtraction();
        assertThrows(IllegalStateException.class,
                () -> changed.text.completeFromBatches(changed.extractCommand(), batches));
    }

    @Test
    void aPolicyChangedBetweenTheBatchesDecidesForTheWholeDocument() {
        Run run = new Run();
        run.readInBatches();
        List<PageBatch> batches = run.searchBatches();
        policy = policy(EnumSet.of(EntityType.DATE_OF_BIRTH));
        run.find.completeFromBatches(run.detectCommand(), batches);
        assertTrue(run.findings.stream().allMatch(f -> f.entityType() == EntityType.DATE_OF_BIRTH),
                "the batches had searched for SSN too; the policy of the end result is one policy");
        assertFalse(run.findings.isEmpty());
    }

    // --------------------------------------------------------------------------------------------- helpers

    private static List<String> places(List<Finding> findings) {
        return findings.stream().map(f -> f.entityType() + " " + f.location() + " " + f.confidence().value() + " "
                + f.detectorSource() + " " + f.status()).sorted().toList();
    }

    private TenantRedactionPolicy policy(Set<EntityType> kinds) {
        return new TenantRedactionPolicy(tenantId, kinds, 0.80, ReviewMode.UNCERTAIN_ONLY, true, Instant.now());
    }

    /** One document with its own stores, and the real services on them. */
    private final class Run {
        final Map<String, byte[]> files = new ConcurrentHashMap<>();
        final Map<UUID, Artifact> artifacts = new ConcurrentHashMap<>();
        final List<Finding> findings = new ArrayList<>();
        final List<ArtifactStorageReference> discarded = new ArrayList<>();
        PageBatchSettings settings = SETTINGS;
        final DocumentPackage pkg = DocumentPackage.create(tenantId, PackageType.MANUAL_UPLOAD,
                ActorId.generate(), PackageMetadata.empty());
        final Document document = Document.create(tenantId, pkg.id(), FileName.of("large.pdf"), MimeType.pdf(),
                pdf.length, Checksum.sha256("a".repeat(64)), DocumentType.PDF);
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
            when(policies.findByTenantId(eq(tenantId))).thenAnswer(i -> Optional.of(policy));
            OcrPort noOcr = input -> {
                throw new AssertionError("typed pages need no OCR");
            };
            TextExtractionPort extraction = new HybridPdfTextExtractionAdapter(new PdfBoxTextExtractionAdapter(),
                    noOcr, new TesseractOcrProperties());
            AuditService audit = new AuditService(audits::add);
            LocalRuleBasedDetectionAdapter rules = new LocalRuleBasedDetectionAdapter();
            text = new TextExtractionService(documents, packages, artifactStore, storage, extraction, checksums,
                    mock(ProcessingLockPort.class), events::add, audit);
            find = new SensitiveDataDetectionService(documents, packages, artifactStore, storage,
                    ai, rules, rules, policies, extraction, findingStore,
                    events::add, audit, checksums);
        }

        ExtractDocumentTextCommand extractCommand() {
            if (document.status() == DocumentStatus.UPLOADED) document.startExtraction();
            return ExtractDocumentTextCommand.of(document.id(), pkg.id(), tenantId, "k", "t", CorrelationId.generate());
        }

        DetectSensitiveDataCommand detectCommand() {
            return DetectSensitiveDataCommand.of(document.id(), pkg.id(), tenantId, CorrelationId.generate());
        }

        List<PageBatch> readBatches() {
            List<PageBatch> batches = PageBatchPlan.text(PAGES, SETTINGS).stream()
                    .map(r -> new PageBatch(UUID.randomUUID(), r.from(), r.to())).toList();
            // In no particular order, as workers finish them.
            List<PageBatch> order = new ArrayList<>(batches);
            Collections.shuffle(order, new java.util.Random(49));
            order.forEach(b -> text.extractBatch(command(b)));
            return batches;
        }

        void readInBatches() {
            List<PageBatch> batches = readBatches();
            StagedStepScope.tracking(new ArrayList<>(), discarded, () ->
                    text.completeFromBatches(extractCommand(), batches));
        }

        List<PageBatch> searchBatches() {
            List<PageBatch> batches = PageBatchPlan.find(document.pageCount(), settings).stream()
                    .map(r -> new PageBatch(UUID.randomUUID(), r.from(), r.to())).toList();
            List<PageBatch> order = new ArrayList<>(batches);
            Collections.shuffle(order, new java.util.Random(50));
            order.forEach(b -> find.detectBatch(command(b)));
            return batches;
        }

        void findInBatches() {
            List<PageBatch> batches = searchBatches();
            assertTrue(findings.isEmpty(), "nothing is saved as a finding before the document is gathered");
            StagedStepScope.tracking(new ArrayList<>(), discarded, () ->
                    find.completeFromBatches(detectCommand(), batches));
        }

        PageBatchCommand command(PageBatch batch) {
            return new PageBatchCommand(batch, document.id(), pkg.id(), tenantId, CorrelationId.generate());
        }

        String storedText() {
            Artifact stored = artifacts.values().stream().filter(a -> a.artifactType() == ArtifactType.EXTRACTED_TEXT)
                    .findFirst().orElseThrow();
            return new String(files.get(stored.storageReference().key()), java.nio.charset.StandardCharsets.UTF_8);
        }

        List<byte[]> findingFiles() {
            List<byte[]> found = files.entrySet().stream().filter(e -> e.getKey().contains("FINDINGS_BATCH"))
                    .map(Map.Entry::getValue).toList();
            assertEquals(28, found.size());
            return found;
        }
    }

    /** Finds the dates whose label is at the foot of the page before, only when that page is in the same request. */
    private static SensitiveContentAnalysisPort.AnalysisOutput standInAi(
            SensitiveContentAnalysisPort.AnalysisRequest request) {
        List<SensitiveContentAnalysisPort.DiscoveredSensitiveContent> found = new ArrayList<>();
        Map<Integer, String> pages = new java.util.HashMap<>();
        request.pages().forEach(p -> pages.put(p.pageNumber(), p.text()));
        pages.forEach((number, text) -> {
            Matcher date = DOB.matcher(text);
            String before = pages.get(number - 1);
            if (date.find() && before != null && before.contains("date of birth follows")) {
                found.add(new SensitiveContentAnalysisPort.DiscoveredSensitiveContent("DATE_OF_BIRTH", number,
                        date.group(1), "Applicant " + date.group(1), 0.95, "stand-in-ai", "1"));
            }
        });
        return new SensitiveContentAnalysisPort.AnalysisOutput(found);
    }

    private static byte[] fiveHundredPages() throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int n = 1; n <= PAGES; n++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    line(cs, font, 720, "Synthetic loan file, page " + n + " of " + PAGES);
                    line(cs, font, 690, "Borrower SSN: 219-09-" + String.format("%04d", 1000 + n));
                    if (n % 20 == 1 && n > 1) {
                        line(cs, font, 660, "Applicant 04/" + String.format("%02d", 1 + n % 28) + "/1961 is the birthday");
                    }
                    if (n % 20 == 0) line(cs, font, 100, "The borrower's date of birth follows");
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static void line(PDPageContentStream cs, PDType1Font font, float y, String text) throws IOException {
        cs.beginText();
        cs.setFont(font, 12);
        cs.newLineAtOffset(72, y);
        cs.showText(text);
        cs.endText();
    }
}
