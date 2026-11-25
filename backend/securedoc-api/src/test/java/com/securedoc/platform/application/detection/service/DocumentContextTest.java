package com.securedoc.platform.application.detection.service;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.securedoc.platform.application.detection.command.DetectSensitiveDataCommand;
import com.securedoc.platform.application.extraction.service.TextExtractionService;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.intake.CaseInstructionsService;
import com.securedoc.platform.application.pipeline.PageBatch;
import com.securedoc.platform.application.pipeline.PageBatchCommand;
import com.securedoc.platform.application.pipeline.StagedStepScope;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.CaseInstructionsRepository;
import com.securedoc.platform.application.port.out.ChecksumPort;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.FindingRepository;
import com.securedoc.platform.application.port.out.PackageLifecycleRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.ProcessingLockPort;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.AnalysisOutput;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.AnalysisPolicy.Ask;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.AnalysisRequest;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.DiscoveredSensitiveContent;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.application.port.out.TextExtractionPort;
import com.securedoc.platform.application.port.out.TransactionPort;
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
import com.securedoc.platform.domain.business.VisionSettings;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.DocumentType;
import com.securedoc.platform.domain.document.PackageMetadata;
import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.finding.AiModel;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.finding.FindingStatus;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.FileName;
import com.securedoc.platform.domain.vo.MimeType;
import com.securedoc.platform.infrastructure.detection.LocalRuleBasedDetectionAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * Task 0065: a document's people are read once, every batch looks for their names, and neither the names nor the case's
 * instructions are anywhere but in encrypted storage. The AI is a stand-in; invented names only.
 */
class DocumentContextTest {
    private List<String> texts = TEXTS;
    private SensitiveContentAnalysisPort ai = this::answer;
    private static final List<String> TEXTS = List.of(
            "Complaint of Hope Quillfeather (\"Quill\") for her son, called Sparrowhawk here.",
            "The ledger was kept by Quill until June.\nA quill was found.",
            "SPARROWHAWK was absent. The Sparrowhawk Inn was closed.",
            "Nothing here.");

    private final TenantId tenant = TenantId.generate();
    private final ActorId person = ActorId.generate();
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final Map<UUID, Artifact> artifacts = new ConcurrentHashMap<>();
    private final List<Finding> findings = new ArrayList<>();
    private final List<AuditRecord> audits = new ArrayList<>();
    private final List<Object> events = new ArrayList<>();
    private final List<AnalysisRequest> asked = new ArrayList<>();
    private final List<ArtifactStorageReference> discarded = new ArrayList<>();
    private final Map<PackageId, CaseInstructionsRepository.Stored> rows = new ConcurrentHashMap<>();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final DocumentPackage pkg = DocumentPackage.create(tenant, PackageType.MANUAL_UPLOAD, person,
            PackageMetadata.empty());
    private final Document document = Document.create(tenant, pkg.id(), FileName.of("filing.pdf"), MimeType.pdf(), 10,
            Checksum.sha256("a".repeat(64)), DocumentType.PDF);
    private TenantRedactionPolicy policy = policy(true, true);
    private CaseInstructionsService instructions;
    private SensitiveDataDetectionService find;
    private DocumentContext context;

    @BeforeEach
    void setUp() {
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("ROOT");
        logs.start();
        root.addAppender(logs);
        pkg.addDocument(document);
        StoragePort storage = new StoragePort() {
            @Override public ArtifactStorageReference store(TenantId t, String path, InputStream content, long size, String mime) {
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
                return Checksum.sha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.readAllBytes())));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        DocumentRepository documents = mock(DocumentRepository.class);
        when(documents.findByIdAndTenantId(any(), any())).thenReturn(Optional.of(document));
        PackageRepository packages = mock(PackageRepository.class);
        when(packages.findByIdAndTenantId(any(), any())).thenReturn(Optional.of(pkg));
        ArtifactRepository artifactStore = mock(ArtifactRepository.class);
        when(artifactStore.findByDocumentIdAndTenantId(any(), any())).thenAnswer(i -> new ArrayList<>(artifacts.values()));
        org.mockito.Mockito.doAnswer(i -> {
            Artifact a = i.getArgument(0);
            artifacts.put(a.id().value(), a);
            return null;
        }).when(artifactStore).save(any());
        FindingRepository findingStore = mock(FindingRepository.class);
        org.mockito.Mockito.doAnswer(i -> {
            findings.addAll(i.getArgument(0));
            return null;
        }).when(findingStore).saveAll(any());
        TenantRedactionPolicyRepository policies = mock(TenantRedactionPolicyRepository.class);
        when(policies.findByTenantId(any())).thenAnswer(i -> Optional.of(policy));
        TextExtractionPort extraction = mock(TextExtractionPort.class);
        when(extraction.extract(any(), any())).thenAnswer(i -> output(null));
        when(extraction.extractPages(any(), any(), any())).thenAnswer(i -> output(i.getArgument(2)));
        AuditService audit = new AuditService(audits::add);
        CaseInstructionsRepository store = new CaseInstructionsRepository() {
            @Override public Optional<Stored> find(TenantId t, PackageId p) { return Optional.ofNullable(rows.get(p)); }
            @Override public void save(TenantId t, PackageId p, Stored s) { rows.put(p, s); }
            @Override public void remove(TenantId t, PackageId p) { rows.remove(p); }
        };
        PackageLifecycleRepository lifecycles = mock(PackageLifecycleRepository.class);
        when(lifecycles.findByPackageIdAndTenantId(any(), any())).thenReturn(Optional.empty());
        instructions = new CaseInstructionsService(store, storage, packages, policies, lifecycles, TransactionPort.DIRECT, audit);
        ProcessingLockPort locks = mock(ProcessingLockPort.class);
        when(locks.tryAcquire(any(), any())).thenReturn(Optional.of("token"));
        context = new DocumentContext(r -> ai.analyze(r), artifactStore, storage, checksums, instructions, locks, TransactionPort.DIRECT);
        LocalRuleBasedDetectionAdapter rules = new LocalRuleBasedDetectionAdapter();
        find = new SensitiveDataDetectionService(documents, packages, artifactStore, storage, r -> ai.analyze(r), rules, rules, policies,
                extraction, findingStore, events::add, audit, checksums).withContext(context);
        artifact(ArtifactType.ORIGINAL, "original", new byte[]{1});
        artifact(ArtifactType.EXTRACTED_TEXT, "text", String.join(TextExtractionService.PAGE_SEPARATOR, TEXTS)
                .getBytes(StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("ROOT")).detachAppender(logs);
    }

    @Test
    void namesOfTheFirstPageAreFoundOnLaterPagesAndOnlyWithTheSetting() {
        processing();
        find.execute(detect());
        assertEquals(List.of("1:Hope Quillfeather", "1:Quill", "1:Sparrowhawk", "2:Quill", "3:SPARROWHAWK"), found());
        assertTrue(findings.stream().filter(f -> f.entityType() == EntityType.PERSON_NAME)
                .allMatch(f -> f.status() == FindingStatus.DETECTED), "a person decides every name");
        assertEquals(List.of(Ask.SUMMARY, Ask.KINDS, Ask.KNOWN_NAMES), asked.stream().map(r -> r.policy().ask()).toList());
        assertFalse(artifacts.values().stream().anyMatch(a -> a.artifactType() == ArtifactType.DOCUMENT_SUMMARY),
                "a document searched as a whole stores nothing");
        assertNoNamesOutsideStorage();
    }

    @Test
    void offOrWithoutPersonNameNothingIsAskedOrStored() {
        processing();
        policy = policy(false, true);
        find.execute(detect());
        assertEquals(List.of(), found());
        assertEquals(List.of(Ask.KINDS), asked.stream().map(r -> r.policy().ask()).toList());
        asked.clear();
        policy = policy(true, false);
        assertFalse(DocumentContext.wanted(policy));
        assertEquals("", context.stamp(document, policy));
    }

    @Test
    void pageBatchesShareOneSummaryThatGoesWhenTheyAreGathered() {
        processing();
        find.prepareBatch(detect());
        List<PageBatch> batches = List.of(new PageBatch(UUID.randomUUID(), 1, 2), new PageBatch(UUID.randomUUID(), 3, 4));
        batches.forEach(b -> find.detectBatch(new PageBatchCommand(b, document.id(), pkg.id(), tenant, CorrelationId.generate())));
        Artifact summary = artifacts.values().stream().filter(a -> a.artifactType() == ArtifactType.DOCUMENT_SUMMARY)
                .findFirst().orElseThrow();
        assertEquals(ArtifactStatus.AVAILABLE, summary.status());
        StagedStepScope.tracking(new ArrayList<>(), discarded, () -> find.completeFromBatches(detect(), batches));
        assertEquals(List.of("1:Hope Quillfeather", "1:Quill", "1:Sparrowhawk", "2:Quill", "3:SPARROWHAWK"), found());
        assertEquals(1, asked.stream().filter(r -> r.policy().ask() == Ask.SUMMARY).count());
        assertEquals(ArtifactStatus.DELETED, summary.status());
        assertTrue(discarded.contains(summary.storageReference()));
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            if (file.getKey().contains("FINDINGS_BATCH")) {
                assertFalse(new String(file.getValue(), StandardCharsets.ISO_8859_1).contains("Quill"), "places only");
            }
        }
        assertNoNamesOutsideStorage();
    }

    @Test
    void aChangedSummaryFileIsRefused() {
        processing();
        find.prepareBatch(detect());
        String key = artifacts.values().stream().filter(a -> a.artifactType() == ArtifactType.DOCUMENT_SUMMARY)
                .findFirst().orElseThrow().storageReference().key();
        files.put(key, "document-names-1\nnone\n\nOther".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> context.of(document, policy));
    }

    @Test
    void instructionsAreGivenToTheFirstPassAndAChangeMakesItAnew() {
        instructions.change(tenant, person, pkg.id(), "Protect Wrenfield, the child.", CorrelationId.generate());
        processing();
        find.prepareBatch(detect());
        assertTrue(asked.get(0).pages().get(0).text().startsWith("Instructions for this case:\nProtect Wrenfield, the child.\n\n"));
        assertFalse(asked.toString().contains("Wrenfield"), "no text form shows it");
        String before = context.stamp(document, policy);
        rows.computeIfPresent(pkg.id(), (id, s) -> new CaseInstructionsRepository.Stored(UUID.randomUUID(), s.reference()));
        assertFalse(before.equals(context.stamp(document, policy)), "batches of both are never mixed");
        find.prepareBatch(detect());
        assertEquals(2, asked.stream().filter(r -> r.policy().ask() == Ask.SUMMARY).count());
        assertEquals("characters=29", audits.stream().filter(a -> a.action() == AuditAction.CASE_INSTRUCTIONS_CHANGED)
                .findFirst().orElseThrow().detail());
        assertNoNamesOutsideStorage();
    }

    @Test
    void instructionsAreOneFileOfTheCaseAndRefusedWhereTheyAreOffOrTooLong() {
        CorrelationId id = CorrelationId.generate();
        instructions.change(tenant, person, pkg.id(), "Protect <Wrenfield>\r\nand no one else\u0007", id);
        assertEquals("Protect  Wrenfield \nand no one else", instructions.find(tenant, pkg.id()).orElseThrow().text());
        String first = files.keySet().stream().filter(k -> k.contains("CASE_INSTRUCTIONS")).findFirst().orElseThrow();
        assertTrue(first.startsWith(tenant + "/" + pkg.id() + "/"), "in the case's own folder, erased with it");
        instructions.change(tenant, person, pkg.id(), "Protect Wrenfield", id);
        assertFalse(files.containsKey(first), "the text before is gone");
        assertTrue(instructions.differs(tenant, pkg.id(), "Protect no one"));
        assertTrue(instructions.differs(tenant, pkg.id(), null));
        assertFalse(instructions.differs(tenant, pkg.id(), "Protect Wrenfield"));
        instructions.change(tenant, person, pkg.id(), "Protect Wrenfield", id);
        assertEquals(2, audits.size(), "the same text is no change");
        assertThrows(ValidationException.class, () -> instructions.change(tenant, person, pkg.id(), "x".repeat(2001), id));
        policy = policy(false, true);
        assertThrows(ValidationException.class, () -> instructions.change(tenant, person, pkg.id(), "Protect Robin", id));
        assertThrows(ValidationException.class, () -> instructions.check(tenant, "Protect Robin"));
        instructions.check(tenant, " ");
        instructions.change(tenant, person, pkg.id(), "", id);
        assertTrue(instructions.find(tenant, pkg.id()).isEmpty());
        assertFalse(files.keySet().stream().anyMatch(k -> k.contains("CASE_INSTRUCTIONS")));
    }

    @Test
    void instructionsCanNotBeChangedWhileTheCaseIsProcessed() {
        processing();
        assertThrows(ValidationException.class, () -> instructions.change(tenant, person, pkg.id(), "Protect Robin",
                CorrelationId.generate()));
    }

    @Test
    void formsAreNamesNotRolesOrOrdinaryWords() {
        assertEquals(List.of("Hope Banks", "Banks", "Ray Shore Jr.", "J.B.", "Little Bird"), KnownNames.forms(
                List.of("Hope  Banks", "Ray Shore Jr."), List.of("\"J.B.\"", "the Plaintiff", "Minor", "the child", "B", "Little Bird")));
    }

    @Test
    void aPlaceIsAWholeWordInItsSentence() {
        KnownNames names = new KnownNames(List.of("Hope Banks", "Banks"), List.of());
        List<KnownNames.Place> places = names.places("One thing. Then Hope\nBanks paid. BANKS and Banksia, banks. Banks left.",
                List.of(new int[]{57, 62}));
        assertEquals(List.of("\"Hope Banks\" in: Then Hope Banks paid.", "\"BANKS\" in: BANKS and Banksia, banks."),
                places.stream().map(KnownNames.Place::sentence).toList());
    }

    /**
     * The measurement, with the real model and the product's own code: {@code LEXAVAULT_CONTEXT_PAGES} is a folder outside the
     * repository of JSON files ({@code pages}: texts; {@code wanted}: "page:name", or none when nobody knows), and
     * {@code LEXAVAULT_CONTEXT_OUT} where the numbers go. Off, then on. Counts and times only.
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "LEXAVAULT_CONTEXT_PAGES", matches = ".+")
    void measured() throws Exception {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        StringBuilder out = new StringBuilder();
        try (var all = java.nio.file.Files.list(java.nio.file.Path.of(System.getenv("LEXAVAULT_CONTEXT_PAGES")))) {
            for (java.nio.file.Path file : all.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                var json = mapper.readTree(java.nio.file.Files.readString(file));
                for (boolean on : new boolean[]{false, true}) {
                    DocumentContextTest run = new DocumentContextTest();
                    List<String> pages = new ArrayList<>();
                    json.path("pages").forEach(p -> pages.add(p.asString()));
                    Set<String> wanted = new java.util.TreeSet<>();
                    json.path("wanted").forEach(w -> wanted.add(w.asString()));
                    run.texts = pages;
                    long[] summaryMillis = {0};
                    var real = new com.securedoc.platform.ai.analysis.OllamaSensitiveContentAnalysisAdapter("http://localhost:11434",
                            "qwen3.5:9b", java.time.Duration.ofSeconds(120), 1, 2, java.time.Duration.ofMillis(500), "10m", true, false,
                            8192, 2048, 2, 14_000);
                    run.ai = request -> {
                        long from = System.nanoTime();
                        AnalysisOutput answer = real.analyze(request);
                        if (request.policy().ask() == Ask.SUMMARY) summaryMillis[0] += (System.nanoTime() - from) / 1_000_000;
                        run.asked.add(request);
                        return answer;
                    };
                    run.setUp();
                    if (json.hasNonNull("instructions")) run.instructions.change(run.tenant, run.person, run.pkg.id(),
                            json.path("instructions").asString(), CorrelationId.generate());
                    run.files.put("text", String.join(TextExtractionService.PAGE_SEPARATOR, pages).getBytes(StandardCharsets.UTF_8));
                    run.policy = new TenantRedactionPolicy(run.tenant, Set.of(EntityType.PERSON_NAME), 0.8, ReviewMode.UNCERTAIN_ONLY,
                            true, Instant.now(), true, PageBatchSettings.DEFAULTS, Set.of(), Set.of(), false, VisionSettings.DEFAULTS,
                            false, on);
                    run.processing();
                    long started = System.nanoTime();
                    run.find.execute(run.detect());
                    long millis = (System.nanoTime() - started) / 1_000_000;
                    Set<String> found = new java.util.TreeSet<>(run.found());
                    long later = wanted.stream().filter(w -> !w.startsWith("1:")).count();
                    long caught = wanted.stream().filter(w -> !w.startsWith("1:") && found.contains(w)).count();
                    long other = found.stream().filter(f -> !f.startsWith("1:") && !wanted.contains(f)).count();
                    out.append(file.getFileName()).append(on ? " ON " : " OFF ").append("pages=").append(pages.size());
                    if (!wanted.isEmpty()) out.append(" caught=").append(caught).append('/').append(later).append(" other=").append(other);
                    out.append(" distinctNamesOnPages=").append(found.size()).append(" places=").append(run.findings.size())
                            .append(" sentencesAsked=").append(run.asked.stream().filter(r -> r.policy().ask() == Ask.KNOWN_NAMES)
                                    .mapToInt(r -> r.pages().size()).sum())
                            .append(" summaryMs=").append(summaryMillis[0]).append(" ms=").append(millis).append('\n');
                    run.assertNoLogHolds(wanted);
                    run.tearDown();
                }
            }
        }
        java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("LEXAVAULT_CONTEXT_OUT")), out);
    }

    private void assertNoLogHolds(Set<String> wanted) {
        StringBuilder all = new StringBuilder();
        logs.list.forEach(e -> all.append(e.getFormattedMessage()).append(e.getKeyValuePairs()));
        audits.forEach(a -> all.append(a.detail()));
        wanted.stream().map(w -> w.substring(w.indexOf(':') + 1)).distinct().forEach(name -> assertFalse(
                java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(name) + "\\b").matcher(all).find(), "a name in a log"));
    }

    // ------------------------------------------------------------------------------------------------ stand-ins

    /** The summary of page 1; no names by itself; a word is a person unless the sentence says otherwise. */
    private AnalysisOutput answer(AnalysisRequest request) {
        asked.add(request);
        List<DiscoveredSensitiveContent> out = new ArrayList<>();
        switch (request.policy().ask()) {
            case SUMMARY -> {
                out.add(item("SUMMARY_PERSON", 1, "Hope Quillfeather"));
                out.add(item("SUMMARY_ALSO_CALLED", 1, "Quill"));
                out.add(item("SUMMARY_PERSON", 1, "Sparrowhawk"));
            }
            case KNOWN_NAMES -> request.pages().forEach(p -> {
                if (!p.text().contains("Inn was closed")) out.add(item("PERSON_NAME", p.pageNumber(), "person"));
            });
            default -> { }
        }
        return new AnalysisOutput(out, List.of(AiModel.of("stand-in", "text-" + request.policy().ask(), null)));
    }

    private static DiscoveredSensitiveContent item(String kind, int page, String text) {
        return new DiscoveredSensitiveContent(kind, page, text, "", 0.7, "stand-in", "1");
    }

    private TextExtractionPort.TextExtractionOutput output(Set<Integer> only) {
        List<TextExtractionPort.PageText> pages = new ArrayList<>();
        for (int n = 1; n <= texts.size(); n++) {
            if (only == null || only.contains(n)) pages.add(new TextExtractionPort.PageText(n, 612, 792, texts.get(n - 1)));
        }
        return new TextExtractionPort.TextExtractionOutput(pages, 1, true, false);
    }

    private List<String> found() {
        return findings.stream().filter(f -> f.entityType() == EntityType.PERSON_NAME).map(f -> f.location().pageNumber() + ":"
                + texts.get(f.location().pageNumber() - 1).substring(f.location().startOffset(), f.location().endOffset()))
                .sorted().toList();
    }

    private void assertNoNamesOutsideStorage() {
        StringBuilder all = new StringBuilder();
        logs.list.forEach(e -> all.append(e.getFormattedMessage()).append(e.getMDCPropertyMap()).append(e.getKeyValuePairs()));
        audits.forEach(a -> all.append(a.detail()));
        events.forEach(all::append);
        for (String name : List.of("Quill", "quill", "Sparrowhawk", "SPARROWHAWK", "Wrenfield", "Hope")) {
            assertFalse(all.toString().contains(name), name.length() + " characters found in a log, an audit record or an event");
        }
    }

    private void artifact(ArtifactType type, String key, byte[] content) {
        Artifact artifact = Artifact.create(tenant, document.id(), type, MimeType.pdf(), content.length,
                Checksum.sha256("b".repeat(64)));
        artifact.markAvailable(ArtifactStorageReference.local(key));
        artifacts.put(artifact.id().value(), artifact);
        files.put(key, content);
    }

    private void processing() {
        pkg.submit();
        pkg.startProcessing();
        document.startExtraction();
        document.completeExtraction(java.util.stream.IntStream.range(0, texts.size()).mapToObj(i -> com.securedoc.platform
                .domain.document.Page.withText(i + 1, 612, 792, texts.get(i))).toList());
    }

    private DetectSensitiveDataCommand detect() {
        return DetectSensitiveDataCommand.of(document.id(), pkg.id(), tenant, CorrelationId.generate());
    }

    private TenantRedactionPolicy policy(boolean on, boolean names) {
        return new TenantRedactionPolicy(tenant, names ? Set.of(EntityType.SSN, EntityType.PERSON_NAME) : Set.of(EntityType.SSN), 0.8,
                ReviewMode.UNCERTAIN_ONLY, true, Instant.now(), true, PageBatchSettings.DEFAULTS, Set.of(), Set.of(), true,
                VisionSettings.DEFAULTS, false, on);
    }
}
