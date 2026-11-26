package com.securedoc.platform.api.policy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.securedoc.platform.application.detection.command.DetectSensitiveDataCommand;
import com.securedoc.platform.application.detection.service.SensitiveDataDetectionService;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.FindingRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort;
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
import com.securedoc.platform.domain.business.RedactionCatalog;
import com.securedoc.platform.domain.business.RedactionStyle;
import com.securedoc.platform.domain.business.ReviewMode;
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
import com.securedoc.platform.domain.finding.FindingStatus;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0043: the opt-in pattern kinds through the product's detection service. Off by default: an entity with no
 * stored policy, or with only the V1 four on, gets exactly the findings it got before. Switched on, each kind becomes a
 * finding with a box, auto-confirmed or sent to review by the same confidence rule as every pattern finding. The AI is
 * never asked for these kinds. No value is kept in any finding, audit record, event or log line. Fake values only.
 */
class OptInPatternKindsDetectionTest {

    private static final String SSN = "412-55-7531";
    private static final String ROUTING = "032000719";      // fake: made-up prefix + ABA check digit
    private static final String ROUTING_MISREAD = "O32000719"; // OCR read the 0 as the letter O
    private static final String ACCOUNT = "4410098765";
    private static final String ITIN = "912-78-1234";
    private static final String EIN = "12-3456789";
    private static final String LICENSE = "K7302215";
    private static final String PASSPORT = "X12345678";

    private static final String PAGE = """
            Loan application for a test borrower
            Borrower SSN: 412-55-7531
            Section 2 of the application form, payment details as given by the borrower
            Routing number: 032000719
            Account number: 4410098765
            ITIN: 912-78-1234
            Employer EIN: 12-3456789
            Driver's license number: K7302215
            Passport number: X12345678
            Second routing number: O32000719
            End of page""";

    /** Found in text; signatures and face photos are found on the page image (task 0064, VisionDetectionTest). */
    private static final Set<EntityType> NEW_KINDS = RedactionCatalog.entries().stream()
            .filter(e -> !e.onByDefault() && e.detection() == RedactionCatalog.DetectionMethod.PATTERN).map(RedactionCatalog.Entry::kind).collect(Collectors.toSet());

    private final TenantId tenantId = TenantId.of(UUID.randomUUID());
    private final List<Finding> savedFindings = new CopyOnWriteArrayList<>();
    private final List<AuditRecord> auditRecords = new CopyOnWriteArrayList<>();
    private final List<DomainEvent> events = new CopyOnWriteArrayList<>();
    private final List<SensitiveContentAnalysisPort.AnalysisRequest> aiRequests = new CopyOnWriteArrayList<>();
    private List<SensitiveContentAnalysisPort.DiscoveredSensitiveContent> aiAnswer = List.of();
    private TenantRedactionPolicy storedPolicy;
    private String pageText = PAGE;
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
    }

    @Test
    void anEntityWithoutAStoredPolicyGetsNoNewKindFinding() {
        storedPolicy = null;
        run();
        assertEquals(Set.of(EntityType.SSN), types(), "only the V1 kinds, as before");
        assertEquals(Set.of(EntityType.SSN, EntityType.DATE_OF_BIRTH, EntityType.CREDIT_CARD_NUMBER,
                EntityType.CREDIT_SCORE), aiRequests.get(0).policy().enabledEntityTypes());
    }

    @Test
    void theDefaultsHoldNoOptInKind() {
        assertEquals(RedactionCatalog.onByDefault(),
                TenantRedactionPolicy.defaults(tenantId).enabledEntityTypes());
        assertTrue(TenantRedactionPolicy.defaults(tenantId).enabledEntityTypes().stream().noneMatch(NEW_KINDS::contains));
    }

    @Test
    void eachKindIsFoundOnlyWhenSwitchedOn() {
        for (EntityType kind : NEW_KINDS) {
            savedFindings.clear();
            aiRequests.clear();
            storedPolicy = policy(EnumSet.of(EntityType.SSN, kind));
            run();
            assertEquals(EnumSet.of(EntityType.SSN, kind), EnumSet.copyOf(types()), kind + " alone switched on");
            // Task 0099: and for the card number, a locked item that is always on.
            assertEquals(Set.of(EntityType.SSN, EntityType.CREDIT_CARD_NUMBER), aiRequests.get(0).policy().enabledEntityTypes(),
                    "the AI is asked only for V1 kinds");
        }
    }

    @Test
    void switchedOnFindingsHaveBoxesAndFollowTheConfidenceRule() {
        EnumSet<EntityType> all = EnumSet.copyOf(RedactionCatalog.onByDefault());
        all.addAll(NEW_KINDS);
        storedPolicy = policy(all);
        run();

        Map<EntityType, Long> counts = savedFindings.stream()
                .collect(Collectors.groupingBy(Finding::entityType, Collectors.counting()));
        assertEquals(2L, counts.get(EntityType.BANK_ROUTING_NUMBER), "the clean one and the misread one");
        for (EntityType kind : NEW_KINDS) {
            assertTrue(counts.getOrDefault(kind, 0L) >= 1, kind + " found");
        }
        for (Finding f : savedFindings) {
            assertTrue(f.location().hasBoundingBox(), "every pattern finding is located");
            boolean confirmed = f.status() == FindingStatus.CONFIRMED;
            assertEquals(f.confidence().value() >= 0.80, confirmed, "auto-confirmed exactly when at or above 0.80");
        }
        assertTrue(savedFindings.stream().anyMatch(f -> f.entityType() == EntityType.BANK_ROUTING_NUMBER
                && f.status() == FindingStatus.DETECTED), "the corrected OCR misread goes to a person");
    }

    @Test
    void reviewEverythingModeSendsTheNewKindsToAPersonToo() {
        storedPolicy = new TenantRedactionPolicy(tenantId, EnumSet.copyOf(NEW_KINDS), 0.80, ReviewMode.REVIEW_ALL,
                true, Instant.now());
        run();
        assertFalse(savedFindings.isEmpty());
        assertTrue(savedFindings.stream().allMatch(f -> f.status() == FindingStatus.DETECTED));
        // Task 0099 (changed): no V1 kind chosen, but the locked card number is always on, so the AI is asked for it.
        assertEquals(Set.of(EntityType.CREDIT_CARD_NUMBER), aiRequests.get(0).policy().enabledEntityTypes());
    }

    @Test
    void noNewKindValueIsKeptAnywhere() {
        storedPolicy = policy(EnumSet.copyOf(NEW_KINDS));
        run();
        assertFalse(savedFindings.isEmpty());
        List<String> needles = new ArrayList<>();
        for (String value : List.of(ROUTING, ROUTING_MISREAD, ACCOUNT, ITIN, EIN, LICENSE, PASSPORT)) {
            needles.add(value);
            String digits = value.replaceAll("[^0-9]", "");
            needles.add(digits);
            needles.add(digits.substring(digits.length() - 4));
        }
        List<String> texts = new ArrayList<>();
        savedFindings.forEach(f -> texts.add(NoRedactedValueSavedTest.dump(f)));
        auditRecords.forEach(a -> texts.add(NoRedactedValueSavedTest.dump(a)));
        events.forEach(e -> texts.add(NoRedactedValueSavedTest.dump(e)));
        logs.list.forEach(l -> texts.add(l.getFormattedMessage() + " " + l.getMDCPropertyMap()));
        for (String raw : texts) {
            // Ids and times are not values; drop them so a last-4 cannot match one of their digits by chance.
            String text = raw.replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "")
                    .replaceAll("\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z", "");
            for (String needle : needles) {
                assertFalse(text.contains(needle), "a stored or logged text holds part of a value");
            }
        }
    }

    /**
     * Task 0047, item 3 (owner: ITIN wins): an "SSN or ITIN" value starting with 9 is one finding. Both orders of the
     * label, with both kinds on and with each kind alone. Its box is the value's box either way.
     */
    @Test
    void ssnOrItinOnOneBoxIsOneFindingAndItinWinsWhenBothAreOn() {
        for (String label : List.of("SSN or ITIN: ", "ITIN or SSN: ")) {
            pageText = "Tax form for a test borrower\n" + label + ITIN + "\nEnd of page";
            int start = pageText.indexOf(ITIN);

            Map<EntityType, List<Finding>> both = runWith(EnumSet.of(EntityType.SSN, EntityType.ITIN));
            assertEquals(Set.of(EntityType.ITIN), both.keySet(), label + "both on: the ITIN only");
            assertEquals(1, both.get(EntityType.ITIN).size(), label + "both on: one finding");
            Finding itin = both.get(EntityType.ITIN).get(0);
            assertEquals(start, itin.location().startOffset());
            assertEquals(start + ITIN.length(), itin.location().endOffset());
            assertTrue(itin.location().hasBoundingBox());

            Map<EntityType, List<Finding>> ssnOnly = runWith(EnumSet.of(EntityType.SSN));
            assertEquals(Set.of(EntityType.SSN), ssnOnly.keySet(), label + "SSN alone: still found, as an SSN");
            assertEquals(1, ssnOnly.get(EntityType.SSN).size());

            Map<EntityType, List<Finding>> itinOnly = runWith(EnumSet.of(EntityType.ITIN));
            assertEquals(Set.of(EntityType.ITIN), itinOnly.keySet(), label + "ITIN alone: found as an ITIN");
            assertEquals(1, itinOnly.get(EntityType.ITIN).size());
        }
    }

    @Test
    void anSsnNextToAnItinStaysAnSsn() {
        pageText = "Borrower SSN: " + SSN + "\nSpouse ITIN: " + ITIN + "\nEnd of page";
        Map<EntityType, List<Finding>> found = runWith(EnumSet.of(EntityType.SSN, EntityType.ITIN));
        assertEquals(1, found.get(EntityType.SSN).size(), "a real SSN is not touched by the ITIN rule");
        assertEquals(1, found.get(EntityType.ITIN).size());
    }

    /** Item 4 through the service: only a driver's licence label finds the number. */
    @Test
    void onlyADriversLicenceLabelFindsALicenceNumber() {
        pageText = """
                Business license number: K7302215
                Contractor license K7302216
                Marriage license number 44712290
                Driver's license no. K7302217
                DL # K7302218
                End of page""";
        List<Finding> found = runWith(EnumSet.of(EntityType.DRIVERS_LICENSE_NUMBER))
                .getOrDefault(EntityType.DRIVERS_LICENSE_NUMBER, List.of());
        List<Integer> starts = found.stream().map(f -> f.location().startOffset()).sorted().toList();
        assertEquals(List.of(pageText.indexOf("K7302217"), pageText.indexOf("K7302218")), starts);
    }

    /**
     * Task 0062, changed by task 0099: the locked items are found whatever is chosen, also for an entity from before
     * the catalog whose policy was not saved since (it kept its card switch off and had no CVV or PIN).
     */
    @Test
    void theLockedItemsAreFoundAlsoForAnEntityFromBeforeTheCatalog() {
        pageText = "Borrower SSN: " + SSN + "\nCredit card number: 4111 1111 1111 1111\nCVV: 739\nPIN: 8264\nEnd of page";
        Map<EntityType, List<Finding>> before = runWith(EnumSet.of(EntityType.SSN));
        assertEquals(EnumSet.of(EntityType.SSN, EntityType.CREDIT_CARD_NUMBER, EntityType.CVV, EntityType.PIN),
                EnumSet.copyOf(before.keySet()), "before the catalog: what was on, and the locked items");
        assertEquals(Set.of(EntityType.SSN, EntityType.CREDIT_CARD_NUMBER),
                aiRequests.get(aiRequests.size() - 1).policy().enabledEntityTypes());

        savedFindings.clear();
        storedPolicy = new TenantRedactionPolicy(tenantId, EnumSet.of(EntityType.SSN), 0.80, ReviewMode.UNCERTAIN_ONLY,
                true, Instant.now(), true, com.securedoc.platform.domain.business.PageBatchSettings.DEFAULTS, Set.of(),
                Set.of(EntityType.SSN), true);
        run();
        assertEquals(EnumSet.of(EntityType.SSN, EntityType.CREDIT_CARD_NUMBER, EntityType.CVV, EntityType.PIN),
                EnumSet.copyOf(types()));
        for (Finding f : savedFindings) {
            assertEquals(f.entityType() == EntityType.SSN ? RedactionStyle.KEEP_LAST_FOUR : RedactionStyle.REMOVE_FULLY,
                    f.redactionStyle(), "the style of the moment goes with the finding");
        }
    }

    /* Task 0072: Person name: off by default, the AI only, every place on the page, never without a person. */

    private static final String NAMES = """
            Letter of DELPHINE QUARRINGTON to the court
            Ms. Quarrington asks that Delphine Quarrington be heard. Quarringtonville is a town.
            SSN: 412-55-7531
            End of page""";

    private static SensitiveContentAnalysisPort.DiscoveredSensitiveContent name(String text) {
        return new SensitiveContentAnalysisPort.DiscoveredSensitiveContent("PERSON_NAME", 1, text, "", 0.99, "ai", "1");
    }

    @Test
    void aNameIsFoundInEveryPlaceAndAlwaysGoesToAPerson() {
        pageText = NAMES;
        aiAnswer = List.of(name("Delphine Quarrington"), name("Quarrington"), name("412-55-7531"));
        Map<EntityType, List<Finding>> found = runWith(EnumSet.of(EntityType.SSN, EntityType.PERSON_NAME));
        assertEquals(Set.of(EntityType.SSN, EntityType.PERSON_NAME, EntityType.CREDIT_CARD_NUMBER),
                aiRequests.get(0).policy().enabledEntityTypes(), "task 0099: the locked card number too");
        List<Finding> names = found.get(EntityType.PERSON_NAME);
        assertEquals(3, names.size(), "both full names and the last name alone; not the town, not the number");
        for (Finding f : names) {
            assertTrue(f.location().hasBoundingBox());
            assertEquals(FindingStatus.DETECTED, f.status(), "0.99 is above the threshold, and still a person decides");
            assertEquals(RedactionStyle.REMOVE_FULLY, f.redactionStyle());
        }
        assertTrue(found.get(EntityType.SSN).stream().allMatch(f -> f.status() == FindingStatus.CONFIRMED));
        for (String text : java.util.stream.Stream.of(savedFindings.stream().map(NoRedactedValueSavedTest::dump),
                auditRecords.stream().map(NoRedactedValueSavedTest::dump), events.stream().map(NoRedactedValueSavedTest::dump),
                logs.list.stream().map(l -> l.getFormattedMessage())).flatMap(s -> s).toList()) {
            assertFalse(text.toLowerCase().contains("quarrington") || text.toLowerCase().contains("delphine"), "a name is kept nowhere");
        }
    }

    @Test
    void offMeansOffEvenWhenTheModelAnswersWithNames() {
        pageText = NAMES;
        aiAnswer = List.of(name("Delphine Quarrington"));
        assertEquals(Set.of(EntityType.SSN), runWith(EnumSet.of(EntityType.SSN)).keySet());
        storedPolicy = null;
        savedFindings.clear();
        run();
        assertEquals(Set.of(EntityType.SSN), types());
    }

    @Test
    void aNameWithoutAWholeWordPlaceIsNotLost() {
        pageText = NAMES;
        aiAnswer = List.of(name("Quarringtonv"));
        List<Finding> names = runWith(EnumSet.of(EntityType.PERSON_NAME)).get(EntityType.PERSON_NAME);
        assertEquals(1, names.size());
        assertFalse(names.get(0).location().hasBoundingBox());
        assertEquals(FindingStatus.DETECTED, names.get(0).status());
    }

    private Map<EntityType, List<Finding>> runWith(Set<EntityType> enabled) {
        savedFindings.clear();
        storedPolicy = policy(enabled);
        run();
        return savedFindings.stream().collect(Collectors.groupingBy(Finding::entityType));
    }

    // --- wiring ------------------------------------------------------------------------------

    private TenantRedactionPolicy policy(Set<EntityType> enabled) {
        return new TenantRedactionPolicy(tenantId, enabled, 0.80, ReviewMode.UNCERTAIN_ONLY, true, Instant.now());
    }

    private Set<EntityType> types() {
        return savedFindings.stream().map(Finding::entityType).collect(Collectors.toSet());
    }

    private void run() {
        LocalRuleBasedDetectionAdapter rules = new LocalRuleBasedDetectionAdapter();
        SensitiveContentAnalysisPort ai = request -> {
            aiRequests.add(request);
            return new SensitiveContentAnalysisPort.AnalysisOutput(aiAnswer);
        };
        SensitiveDataDetectionService service = new SensitiveDataDetectionService(
                new Documents(), new Packages(), new Artifacts(), new Storage(),
                ai, rules, rules, new Policies(), new Extraction(), new Findings(),
                events::add, new AuditService(auditRecords::add));
        fixture();
        service.execute(DetectSensitiveDataCommand.of(
                fixture.document.id(), fixture.pkg.id(), tenantId, CorrelationId.generate()));
    }

    private Fixture fixture;

    private void fixture() {
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
    }

    private record Fixture(DocumentPackage pkg, Document document, Artifact original) {}

    private final class Findings implements FindingRepository {
        @Override public void save(Finding finding) { savedFindings.add(finding); }
        @Override public void saveAll(List<Finding> findings) { savedFindings.addAll(findings); }
        @Override public Optional<Finding> findByIdAndTenantId(FindingId id, TenantId tenant) { return Optional.empty(); }
        @Override public List<Finding> findByDocumentIdAndTenantId(DocumentId documentId, TenantId tenant) { return List.of(); }
        @Override public void deleteByDocumentIdAndTenantId(DocumentId documentId, TenantId tenant) { }
    }

    private final class Policies implements TenantRedactionPolicyRepository {
        @Override public Optional<TenantRedactionPolicy> findByTenantId(TenantId tenant) {
            return Optional.ofNullable(storedPolicy);
        }
        @Override public void save(TenantRedactionPolicy policy) { }
    }

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

    /** One native-text page with a span per word, so findings get boxes as in a real PDF. */
    private final class Extraction implements TextExtractionPort {
        @Override public TextExtractionOutput extract(InputStream content, String mimeType) {
            List<TextSpan> spans = new ArrayList<>();
            String[] lines = pageText.split("\n", -1);
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
            PageText page = new PageText(1, 612, 792, pageText, spans, ExtractionSource.NATIVE_PDF_TEXT, 1.0);
            return new TextExtractionOutput(List.of(page), pageText.length(), true);
        }
    }
}
