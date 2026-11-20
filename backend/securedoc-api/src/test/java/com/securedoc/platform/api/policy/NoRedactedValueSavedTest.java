package com.securedoc.platform.api.policy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.read.ListAppender;
import com.securedoc.platform.ai.analysis.OllamaSensitiveContentAnalysisAdapter;
import com.securedoc.platform.application.detection.command.DetectSensitiveDataCommand;
import com.securedoc.platform.application.detection.service.SensitiveDataDetectionService;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.FindingRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.application.port.out.TextExtractionPort;
import com.securedoc.platform.application.port.out.TextExtractionPort.ExtractionSource;
import com.securedoc.platform.application.port.out.TextExtractionPort.PageText;
import com.securedoc.platform.application.port.out.TextExtractionPort.TextExtractionOutput;
import com.securedoc.platform.application.port.out.TextExtractionPort.TextSpan;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactStorageReference;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.document.DocumentType;
import com.securedoc.platform.domain.document.PackageMetadata;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.document.Page;
import com.securedoc.platform.domain.event.DomainEvent;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.FindingId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.FileName;
import com.securedoc.platform.domain.vo.MimeType;
import com.securedoc.platform.infrastructure.detection.LocalRuleBasedDetectionAdapter;
import com.securedoc.platform.infrastructure.persistence.adapter.JpaFindingRepositoryAdapter;
import com.securedoc.platform.infrastructure.persistence.entity.FindingEntity;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataFindingRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0015, A6 (in the JVM): known fake values go through detection with the rule-based detector
 * and the real Ollama adapter (answering from a local stub server). No saved finding, finding row,
 * audit record, event or log line may hold a value in full, as digits only, or its last 4.
 */
class NoRedactedValueSavedTest {

    // Fake values the detectors accept. Last 4 chosen to be easy to search for.
    private static final String SSN = "412-55-7531";
    private static final String CARD = "4000 0566 5566 5556";
    private static final String DOB = "03/14/1962";

    private static final String PAGE = """
            Loan application for a test borrower
            Borrower SSN: 412-55-7531
            Card Number: 4000 0566 5566 5556
            Date of Birth: 03/14/1962
            End of page""";

    private final TenantId tenantId = TenantId.of(UUID.randomUUID());
    private final List<Finding> savedFindings = new CopyOnWriteArrayList<>();
    private final List<FindingEntity> savedEntities = new CopyOnWriteArrayList<>();
    private final List<AuditRecord> auditRecords = new CopyOnWriteArrayList<>();
    private final List<DomainEvent> events = new CopyOnWriteArrayList<>();

    private HttpServer server;
    private ExecutorService serverExecutor;
    private ListAppender<ILoggingEvent> logs;
    private Logger root;
    private Level rootLevelBefore;

    @BeforeEach
    void captureLogs() {
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        rootLevelBefore = root.getLevel();
        root.setLevel(Level.ALL);
        logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void stop() {
        root.detachAppender(logs);
        root.setLevel(rootLevelBefore);
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void ruleBasedAndAiFindingsKeepNoPartOfAnyValue() throws Exception {
        String aiAnswer = "{\"findings\":["
                + aiFinding("SSN", SSN, "Borrower SSN: " + SSN) + ","
                + aiFinding("CREDIT_CARD_NUMBER", CARD, "Card Number: " + CARD) + ","
                + aiFinding("DATE_OF_BIRTH", DOB, "Date of Birth: " + DOB)
                + "]}";
        startServer(aiAnswer);

        SensitiveDataDetectionService service = service();
        Fixture fixture = fixture();
        service.execute(DetectSensitiveDataCommand.of(
                fixture.document.id(), fixture.pkg.id(), tenantId, CorrelationId.generate()));

        // Detection itself is unchanged: all three types found, by both the AI and the rules.
        Set<EntityType> types = EnumSet.noneOf(EntityType.class);
        savedFindings.forEach(f -> types.add(f.entityType()));
        assertEquals(EnumSet.of(EntityType.SSN, EntityType.CREDIT_CARD_NUMBER, EntityType.DATE_OF_BIRTH), types);
        assertEquals(3, savedFindings.size(), "AI and rule evidence merge into one finding per value");
        assertTrue(savedFindings.stream().allMatch(f -> f.detectorSource().detectorName().contains("+")),
                "both detectors agreed on every value");
        assertEquals(savedFindings.size(), savedEntities.size(), "every finding mapped to a row");
        assertFalse(auditRecords.isEmpty());
        assertFalse(logs.list.isEmpty());

        assertNoValueIn("saved Finding", savedFindings.stream().map(NoRedactedValueSavedTest::dump).toList());
        assertNoValueIn("FindingEntity row", savedEntities.stream().map(NoRedactedValueSavedTest::dump).toList());
        assertNoValueIn("audit record", auditRecords.stream().map(NoRedactedValueSavedTest::dump).toList());
        assertNoValueIn("domain event", events.stream().map(NoRedactedValueSavedTest::dump).toList());
        assertNoValueIn("log line", logs.list.stream().map(NoRedactedValueSavedTest::render).toList());
    }

    @Test
    void aMalformedAiAnswerHoldingTheValuesIsNotEchoedAnywhere() throws Exception {
        // Not JSON: an unquoted token holding the SSN digits. Jackson stops there and its message
        // quotes the token ("Unrecognized token 'SSN412557531'").
        String aiAnswer = "{\"findings\":[{\"pageNumber\":1,\"entityType\":\"SSN\",\"matchedText\":SSN"
                + SSN.replace("-", "") + "}]}";
        startServer(aiAnswer);

        SensitiveDataDetectionService service = service();
        Fixture fixture = fixture();
        RuntimeException failure = assertThrows(RuntimeException.class, () -> service.execute(
                DetectSensitiveDataCommand.of(fixture.document.id(), fixture.pkg.id(), tenantId,
                        CorrelationId.generate())));

        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        assertNoValueIn("exception chain", List.of(trace.toString()));
        assertNoValueIn("audit record", auditRecords.stream().map(NoRedactedValueSavedTest::dump).toList());
        assertNoValueIn("domain event", events.stream().map(NoRedactedValueSavedTest::dump).toList());
        assertNoValueIn("log line", logs.list.stream().map(NoRedactedValueSavedTest::render).toList());
        assertTrue(savedFindings.isEmpty());
    }

    /**
     * Task 0017 B5: a compliance report built from the records this detection wrote (both editions, the full one
     * listing every record) holds no part of any fake value in its PDF text, its audit record or the logs.
     */
    @Test
    void aComplianceReportOfTheseRecordsHoldsNoValue() throws Exception {
        String aiAnswer = "{\"findings\":["
                + aiFinding("SSN", SSN, "Borrower SSN: " + SSN) + ","
                + aiFinding("CREDIT_CARD_NUMBER", CARD, "Card Number: " + CARD) + ","
                + aiFinding("DATE_OF_BIRTH", DOB, "Date of Birth: " + DOB)
                + "]}";
        startServer(aiAnswer);
        Fixture fixture = fixture();
        service().execute(DetectSensitiveDataCommand.of(
                fixture.document.id(), fixture.pkg.id(), tenantId, CorrelationId.generate()));
        assertFalse(auditRecords.isEmpty());

        com.securedoc.platform.domain.tenant.Tenant entity = com.securedoc.platform.domain.tenant.Tenant.reconstitute(
                tenantId, "Leak test entity", "leak-test", Instant.now(),
                com.securedoc.platform.domain.tenant.TenantStatus.ACTIVE,
                com.securedoc.platform.domain.tenant.TenantCapabilityProfile.defaultProfile(), Instant.now());
        // A clock 3 days ahead, so a custom period covering today has already ended.
        java.time.Clock later = java.time.Clock.fixed(Instant.now().plus(Duration.ofDays(3)), java.time.ZoneOffset.UTC);
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        List<AuditRecord> reportAudits = new CopyOnWriteArrayList<>();
        var reports = com.securedoc.platform.api.compliance.InMemoryComplianceReads.service(auditRecords, reportAudits,
                List.of(entity), tenantId, new com.securedoc.platform.application.audit.RequestActorDisplayName(), later);
        var principal = com.securedoc.platform.application.context.AuthenticatedPrincipal.of(
                ActorId.of(UUID.randomUUID()), tenantId, "it-admin", Set.of("TENANT_IT_ADMIN"));
        for (var edition : com.securedoc.platform.application.compliance.ComplianceEdition.values()) {
            var created = reports.create(principal, CorrelationId.generate(),
                    new com.securedoc.platform.application.compliance.ComplianceReportService.Request(
                            "CUSTOM", today.minusDays(1), today.plusDays(1), "UTC"), edition, "IT Administrator");
            String text;
            try (var doc = org.apache.pdfbox.Loader.loadPDF(created.pdf())) {
                text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
            }
            if (edition == com.securedoc.platform.application.compliance.ComplianceEdition.FULL) {
                String flat = text.replaceAll("\\s+", " ");
                assertTrue(flat.contains("Checked a document for sensitive data"), () -> "the detection record is listed: " + flat);
            }
            assertNoValueIn("compliance report PDF (" + edition + ")", List.of(text, text.replaceAll("\\s+", "")));
        }
        assertNoValueIn("compliance report audit record", reportAudits.stream().map(NoRedactedValueSavedTest::dump).toList());
        assertNoValueIn("log line", logs.list.stream().map(NoRedactedValueSavedTest::render).toList());
    }

    // --- the search --------------------------------------------------------------------------

    /** Full value as written, digits only, and last 4, for every fake value. */
    private static List<String> forbidden() {
        List<String> needles = new ArrayList<>();
        for (String value : List.of(SSN, CARD, DOB)) {
            String digits = value.replaceAll("[^0-9]", "");
            needles.add(value);
            needles.add(digits);
            needles.add(digits.substring(digits.length() - 4));
        }
        needles.add(CARD.replace(" ", "-"));
        return needles;
    }

    private static void assertNoValueIn(String where, List<String> texts) {
        for (String text : texts) {
            for (String needle : forbidden()) {
                assertFalse(text.contains(needle),
                        () -> where + " holds '" + needle + "': " + text);
            }
        }
    }

    private static String render(ILoggingEvent event) {
        StringBuilder out = new StringBuilder(event.getLoggerName()).append(' ')
                .append(event.getFormattedMessage()).append(' ').append(event.getMDCPropertyMap());
        if (event.getArgumentArray() != null) {
            for (Object argument : event.getArgumentArray()) {
                out.append(' ').append(argument);
            }
        }
        for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
            out.append(' ').append(t.getClassName()).append(": ").append(t.getMessage());
            for (StackTraceElementProxy frame : t.getStackTraceElementProxyArray()) {
                out.append(' ').append(frame.getSTEAsString());
            }
        }
        return out.toString();
    }

    /** Every field, recursively for this product's own classes, so nothing hides behind toString. */
    static String dump(Object value) {
        StringBuilder out = new StringBuilder();
        dump(value, out, new IdentityHashMap<>(), 0);
        return out.toString();
    }

    private static void dump(Object value, StringBuilder out, Map<Object, Boolean> seen, int depth) {
        if (value == null || depth > 8) {
            out.append("null ");
            return;
        }
        if (value instanceof Collection<?> collection) {
            collection.forEach(item -> dump(item, out, seen, depth + 1));
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                dump(k, out, seen, depth + 1);
                dump(v, out, seen, depth + 1);
            });
            return;
        }
        Class<?> type = value.getClass();
        if (!type.getName().startsWith("com.securedoc") || type.isEnum() || seen.put(value, Boolean.TRUE) != null) {
            out.append(value).append(' ');
            return;
        }
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    out.append(field.getName()).append('=');
                    dump(field.get(value), out, seen, depth + 1);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    throw new AssertionError("cannot read " + c.getName() + "." + field.getName(), e);
                }
            }
        }
    }

    // --- wiring ------------------------------------------------------------------------------

    private SensitiveDataDetectionService service() {
        LocalRuleBasedDetectionAdapter rules = new LocalRuleBasedDetectionAdapter();
        OllamaSensitiveContentAnalysisAdapter ai = new OllamaSensitiveContentAnalysisAdapter(
                "http://localhost:" + server.getAddress().getPort(), "test-model",
                Duration.ofSeconds(5), 1, 1, Duration.ZERO);
        return new SensitiveDataDetectionService(
                new Documents(), new Packages(), new Artifacts(), new Storage(),
                ai, rules, rules, new Policies(), new Extraction(),
                recordingFindings(new JpaFindingRepositoryAdapter(springDataFindings())),
                events::add, new AuditService(auditRecords::add));
    }

    /** Records each domain finding handed to the real JPA adapter, which then maps it to a row. */
    private FindingRepository recordingFindings(FindingRepository jpa) {
        return new FindingRepository() {
            @Override public void save(Finding finding) {
                savedFindings.add(finding);
                jpa.save(finding);
            }
            @Override public void saveAll(List<Finding> findings) {
                savedFindings.addAll(findings);
                jpa.saveAll(findings);
            }
            @Override public Optional<Finding> findByIdAndTenantId(FindingId id, TenantId tenant) {
                return jpa.findByIdAndTenantId(id, tenant);
            }
            @Override public List<Finding> findByDocumentIdAndTenantId(DocumentId documentId, TenantId tenant) {
                return List.of();
            }
            @Override public void deleteByDocumentIdAndTenantId(DocumentId documentId, TenantId tenant) { }
        };
    }

    /** Records every row the real adapter would write, and every finding handed to it. */
    private SpringDataFindingRepository springDataFindings() {
        return (SpringDataFindingRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{SpringDataFindingRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findByIdAndTenantId" -> Optional.empty();
                    case "save" -> {
                        savedEntities.add((FindingEntity) args[0]);
                        yield args[0];
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "SpringDataFindingRepositoryStub";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private Fixture fixture;

    private Fixture fixture() {
        DocumentPackage pkg = DocumentPackage.create(tenantId, PackageType.MANUAL_UPLOAD,
                ActorId.of(UUID.randomUUID()), PackageMetadata.empty());
        Document document = Document.reconstitute(DocumentId.of(UUID.randomUUID()), tenantId, pkg.id(),
                FileName.of("loan.pdf"), MimeType.pdf(), 100, Checksum.sha256("d"), DocumentType.PDF,
                Instant.now(), DocumentStatus.DETECTING,
                new ArrayList<>(IntStream.rangeClosed(1, 1).mapToObj(n -> Page.of(n, 0, 0)).toList()),
                Instant.now());
        pkg.addDocument(document);
        pkg.submit();
        pkg.startProcessing();
        assertEquals(PackageStatus.PROCESSING, pkg.status());
        Artifact original = Artifact.create(tenantId, document.id(), ArtifactType.ORIGINAL,
                MimeType.pdf(), 100, Checksum.sha256("o"));
        original.markAvailable(ArtifactStorageReference.local("original.pdf"));
        fixture = new Fixture(pkg, document, original);
        return fixture;
    }

    private record Fixture(DocumentPackage pkg, Document document, Artifact original) {}

    private final class Documents implements DocumentRepository {
        @Override public void save(Document document) { }
        @Override public Optional<Document> findByIdAndTenantId(DocumentId id, TenantId tenant) {
            return fixture.document.id().equals(id) ? Optional.of(fixture.document) : Optional.empty();
        }
        @Override public List<Document> findByPackageIdAndTenantId(PackageId packageId, TenantId tenant) {
            return List.of(fixture.document);
        }
    }

    private final class Packages implements PackageRepository {
        @Override public void save(DocumentPackage documentPackage) { }
        @Override public Optional<DocumentPackage> findByIdAndTenantId(PackageId id, TenantId tenant) {
            return fixture.pkg.id().equals(id) ? Optional.of(fixture.pkg) : Optional.empty();
        }
        @Override public List<DocumentPackage> findByTenantId(TenantId tenant, int offset, int limit) {
            return List.of(fixture.pkg);
        }
        @Override public long countByTenantId(TenantId tenant) { return 1; }
        @Override public Optional<DocumentPackage> findByTenantIdAndIntakeFingerprint(TenantId tenant, String fingerprint) {
            return Optional.empty();
        }
        @Override public List<DocumentPackage> findByStatus(PackageStatus status, int limit) { return List.of(); }
    }

    private final class Artifacts implements ArtifactRepository {
        @Override public void save(Artifact artifact) { }
        @Override public Optional<Artifact> findByIdAndTenantId(ArtifactId id, TenantId tenant) {
            return Optional.of(fixture.original);
        }
        @Override public List<Artifact> findByDocumentIdAndTenantId(DocumentId documentId, TenantId tenant) {
            return List.of(fixture.original);
        }
    }

    private static final class Storage implements StoragePort {
        @Override public ArtifactStorageReference store(TenantId tenant, String path, InputStream content,
                                                        long sizeBytes, String mimeType) {
            throw new UnsupportedOperationException();
        }
        @Override public InputStream retrieve(ArtifactStorageReference reference) {
            return new ByteArrayInputStream("%PDF-fake".getBytes(StandardCharsets.UTF_8));
        }
        @Override public void delete(ArtifactStorageReference reference) { }
    }

    private static final class Policies implements TenantRedactionPolicyRepository {
        @Override public Optional<TenantRedactionPolicy> findByTenantId(TenantId tenant) { return Optional.empty(); }
        @Override public void save(TenantRedactionPolicy policy) { }
    }

    /** One native-text page with a span per word, so findings get boxes as in a real PDF. */
    private static final class Extraction implements TextExtractionPort {
        @Override public TextExtractionOutput extract(InputStream content, String mimeType) {
            List<TextSpan> spans = new ArrayList<>();
            String[] lines = PAGE.split("\n", -1);
            int offset = 0;
            for (int line = 0; line < lines.length; line++) {
                java.util.regex.Matcher word = java.util.regex.Pattern.compile("\\S+").matcher(lines[line]);
                while (word.find()) {
                    spans.add(new TextSpan(offset + word.start(), offset + word.end(),
                            0.05 + word.start() * 0.01, 0.05 + line * 0.04,
                            (word.end() - word.start()) * 0.01, 0.03));
                }
                offset += lines[line].length() + 1;
            }
            PageText page = new PageText(1, 612, 792, PAGE, spans, ExtractionSource.NATIVE_PDF_TEXT, 1.0);
            return new TextExtractionOutput(List.of(page), PAGE.length(), true);
        }
    }

    // --- stub AI provider --------------------------------------------------------------------

    private static String aiFinding(String type, String value, String context) {
        return "{\"pageNumber\":1,\"entityType\":\"" + type + "\",\"matchedText\":\"" + value
                + "\",\"contextText\":\"" + context + "\",\"confidence\":0.97}";
    }

    private void startServer(String aiAnswer) throws IOException {
        String escaped = aiAnswer.replace("\\", "\\\\").replace("\"", "\\\"");
        String envelope = "{\"message\":{\"content\":\"" + escaped + "\"},\"done_reason\":\"stop\"}";
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/api/chat", exchange -> send(exchange, envelope));
        server.start();
    }

    private static void send(HttpExchange exchange, String body) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
