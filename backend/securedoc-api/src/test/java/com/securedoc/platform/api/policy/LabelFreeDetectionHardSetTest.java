package com.securedoc.platform.api.policy;

import com.securedoc.platform.api.policy.LabelFreeHardSet.Case;
import com.securedoc.platform.api.policy.LabelFreeHardSet.Render;
import com.securedoc.platform.api.policy.LabelFreeHardSet.Variant;
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
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactStorageReference;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.business.RedactionCatalog;
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
import com.securedoc.platform.infrastructure.extraction.HybridPdfTextExtractionAdapter;
import com.securedoc.platform.infrastructure.extraction.PdfBoxTextExtractionAdapter;
import com.securedoc.platform.infrastructure.ocr.TesseractOcrAdapter;
import com.securedoc.platform.infrastructure.ocr.TesseractOcrProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
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
 * Task 0103 (owner's rule: a value is found by what it is, not by its label). The hard set ({@link LabelFreeHardSet})
 * through the product's detection service: real text reading with Tesseract, the pattern rules, every text kind on,
 * and a stand-in AI that finds nothing, so only the rules and OCR count (the hardest case for them; the AI is a second
 * reader on top). Each value is then <b>covered</b> (auto-confirmed), <b>held for a person</b>, or <b>missed</b>; each
 * decoy stays visible, goes to a person (a false alarm a person clears) or is covered (over-redaction).
 *
 * <p>Printed: one table of codes and counts per kind, never a value. Handwritten pages are measured and printed; on
 * them the product does not rely on OCR (the vision model reads them, and the final check asks it again), so they are
 * not held to the OCR targets here.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LabelFreeDetectionHardSetTest {

    /** Locked items and SSN: covered, every one, wherever it stands. */
    private static final Set<EntityType> MUST_ALWAYS_BE_FOUND = EnumSet.of(EntityType.SSN,
            EntityType.CREDIT_CARD_NUMBER, EntityType.CVV, EntityType.PIN);

    /**
     * What no rule can tell from any other number without words around it; the AI (asked for every kind) is the reader
     * there. Listed in the report as known limits; a change that finds them only helps.
     */
    private static final Set<String> KNOWN_LIMITS = Set.of(
            "CREDIT_SCORE/NO_LABEL", "CREDIT_SCORE/FAR_LABEL");

    private final TenantId tenantId = TenantId.of(UUID.randomUUID());
    private final List<Finding> saved = new CopyOnWriteArrayList<>();
    private final List<AuditRecord> audits = new CopyOnWriteArrayList<>();
    private final TextExtractionPort reading = new HybridPdfTextExtractionAdapter(new PdfBoxTextExtractionAdapter(),
            new TesseractOcrAdapter(new TesseractOcrProperties()), new TesseractOcrProperties());
    private List<Case> cases;
    private byte[] source;
    private List<TextExtractionPort.PageText> pages;
    private final Map<Case, String> outcome = new LinkedHashMap<>();
    private Document document;
    private DocumentPackage pkg;
    private Artifact original;

    @BeforeAll
    void runTheHardSet() throws Exception {
        cases = LabelFreeHardSet.cases();
        source = LabelFreeHardSet.pdf(cases);
        pages = reading.extract(new ByteArrayInputStream(source), "application/pdf").pages();
        fixture(cases.size());
        LocalRuleBasedDetectionAdapter rules = new LocalRuleBasedDetectionAdapter();
        SensitiveContentAnalysisPort nothing = request -> new SensitiveContentAnalysisPort.AnalysisOutput(List.of());
        new SensitiveDataDetectionService(new Documents(), new Packages(), new Artifacts(), new Storage(), nothing,
                rules, rules, new Policies(), reading, new Findings(), event -> { }, new AuditService(audits::add))
                .execute(DetectSensitiveDataCommand.of(document.id(), pkg.id(), tenantId, CorrelationId.generate()));
        for (int i = 0; i < cases.size(); i++) outcome.put(cases.get(i), classify(cases.get(i), i + 1));
        print();
    }

    // ------------------------------------------------------------------------------------------------- the targets

    @Test
    void lockedItemsAndSsnAreNeverMissedOnAReadablePage() {
        List<String> missed = cases.stream()
                .filter(c -> !c.decoy() && MUST_ALWAYS_BE_FOUND.contains(c.kind()) && c.render() != Render.HAND)
                .filter(c -> outcome.get(c).startsWith("M")).map(Case::name).toList();
        assertEquals(List.of(), missed, "locked items and SSN: 0 misses");
    }

    @Test
    void lockedCardsAndSsnWithTheirShapeAreCoveredWithoutAPerson() {
        List<String> notCovered = cases.stream()
                .filter(c -> !c.decoy() && (c.kind() == EntityType.SSN || c.kind() == EntityType.CREDIT_CARD_NUMBER))
                .filter(c -> c.render() == Render.TEXT && c.variant() != Variant.OCR_DAMAGE)
                .filter(c -> !outcome.get(c).equals("A")).map(c -> c.name() + "=" + outcome.get(c)).toList();
        assertEquals(List.of(), notCovered, "a valid SSN or card is covered automatically, with or without a label");
    }

    @Test
    void everyOtherKindIsCoveredOrSentToAPerson() {
        List<String> missed = cases.stream()
                .filter(c -> !c.decoy() && !MUST_ALWAYS_BE_FOUND.contains(c.kind()) && c.render() != Render.HAND)
                .filter(c -> !KNOWN_LIMITS.contains(c.name()))
                .filter(c -> outcome.get(c).startsWith("M")).map(Case::name).toList();
        assertEquals(List.of(), missed, "no miss that was not sent to a person");
    }

    @Test
    void decoysAreNeverCoveredWithoutAPerson() {
        List<String> covered = cases.stream().filter(Case::decoy)
                .filter(c -> outcome.get(c).equals("A")).map(Case::name).toList();
        assertEquals(List.of(), covered, "nothing that is not a value is covered automatically");
    }

    @Test
    void noValueIsKeptInAFindingOrTheAudit() {
        List<String> texts = new ArrayList<>();
        saved.forEach(f -> texts.add(NoRedactedValueSavedTest.dump(f)));
        audits.forEach(a -> texts.add(NoRedactedValueSavedTest.dump(a)));
        for (Case c : cases) {
            String digits = c.value().replaceAll("[^0-9]", "");
            // A 3 or 4 digit value (a code, a score) matches ids and times by chance; NoRedactedValueSavedTest has them.
            if (c.decoy() || digits.length() < 6) continue;
            for (String text : texts) {
                String plain = text.replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                        + "[0-9a-fA-F]{12}", "").replaceAll("\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z", "");
                assertFalse(plain.contains(c.value()), c.name() + ": a value in a stored text");
                assertFalse(plain.contains(digits), c.name() + ": a value in a stored text");
            }
        }
    }

    @Test
    void everyFindingIsAboveOrBelowTheThresholdAsItsStatusSays() {
        for (Finding f : saved) {
            if (!f.location().hasBoundingBox()) {
                assertEquals(FindingStatus.DETECTED, f.status(), "a finding without a place waits for a person");
                continue;
            }
            if (RedactionCatalog.alwaysReviewed().contains(f.entityType())) continue;
            assertEquals(f.confidence().value() >= 0.80, f.status() == FindingStatus.CONFIRMED, f.entityType().name());
        }
        assertTrue(saved.size() > 0);
    }

    // ------------------------------------------------------------------------------------------------- measuring

    /**
     * A: covered as its kind (or its family). P: held for a person as its kind. a / p: covered or held, but as another
     * kind (still not delivered). H: the page is held for a person (an unplaced finding of the kind). M: missed. For a
     * decoy: "." stays visible, P held for a person, A covered.
     */
    private String classify(Case c, int page) {
        TextExtractionPort.PageText text = pages.get(page - 1);
        List<Finding> onPage = saved.stream().filter(f -> f.location().pageNumber() == page).toList();
        if (c.decoy()) {
            List<Finding> any = onPage.stream().filter(f -> f.location().hasBoundingBox()).toList();
            if (any.stream().anyMatch(f -> f.status() == FindingStatus.CONFIRMED)) return "A";
            return any.isEmpty() && onPage.isEmpty() ? "." : "P";
        }
        int[] at = locate(text.text(), c.value());
        if (at != null) {
            List<Finding> placed = onPage.stream().filter(f -> f.location().hasBoundingBox()).toList();
            List<Finding> own = placed.stream().filter(f -> family(f.entityType()) == family(c.kind())).toList();
            // The whole value must be under findings: a part left visible is a miss.
            if (covers(own.stream().filter(f -> f.status() == FindingStatus.CONFIRMED).toList(), text.text(), at)) return "A";
            if (covers(own, text.text(), at)) return "P";
            if (covers(placed.stream().filter(f -> f.status() == FindingStatus.CONFIRMED).toList(), text.text(), at)) return "a";
            if (covers(placed, text.text(), at)) return "p";
        }
        boolean pageHeld = onPage.stream().anyMatch(f -> !f.location().hasBoundingBox()
                && (family(f.entityType()) == family(c.kind()) || f.entityType() == EntityType.UNREAD_PAGE));
        if (pageHeld) return "H";
        return at == null ? "M?" : "M";
    }

    /** Every letter and digit of the value's place lies under one of the findings. */
    private static boolean covers(List<Finding> findings, String text, int[] at) {
        for (int i = at[0]; i < at[1]; i++) {
            if (!Character.isLetterOrDigit(text.charAt(i))) continue;
            int index = i;
            if (findings.stream().noneMatch(f -> f.location().startOffset() <= index
                    && index < f.location().endOffset())) return false;
        }
        return true;
    }

    private static EntityType family(EntityType type) {
        return switch (type) {
            case ITIN -> EntityType.SSN;
            case PASSPORT_NUMBER -> EntityType.DRIVERS_LICENSE_NUMBER;
            case PIN -> EntityType.CVV;
            default -> type;
        };
    }

    /** Where the value stands in the read text, OCR's usual letter-for-digit misreads and any spacing allowed. */
    static int[] locate(String text, String value) {
        StringBuilder normal = new StringBuilder();
        List<Integer> index = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isLetterOrDigit(c)) continue;
            normal.append(canonical(c));
            index.add(i);
        }
        StringBuilder wanted = new StringBuilder();
        for (char c : value.toCharArray()) if (Character.isLetterOrDigit(c)) wanted.append(canonical(c));
        int at = normal.indexOf(wanted.toString());
        if (at < 0 || wanted.isEmpty()) return null;
        return new int[]{index.get(at), index.get(at + wanted.length() - 1) + 1};
    }

    private static char canonical(char c) {
        return switch (c) {
            case 'O', 'o' -> '0';
            case 'l', 'I', 'i', '|' -> '1';
            case 'S', 's' -> '5';
            case 'B' -> '8';
            case 'b' -> '6';
            default -> Character.toUpperCase(c);
        };
    }

    /** Codes only. One row per kind, one column per variant; then the decoys and the counts. */
    private void print() {
        StringBuilder out = new StringBuilder("\nTASK-0103 HARD SET (A covered, P person, a/p as another kind, "
                + "H page held, M missed, M? not in the read text)\n");
        out.append(String.format("%-24s", "kind"));
        for (Variant v : Variant.values()) out.append(String.format("%-12s", v.name().toLowerCase()));
        out.append('\n');
        Map<EntityType, Map<Variant, String>> rows = new EnumMap<>(EntityType.class);
        for (Case c : cases) {
            if (!c.decoy()) rows.computeIfAbsent(c.kind(), k -> new EnumMap<>(Variant.class)).put(c.variant(), outcome.get(c));
        }
        rows.forEach((kind, row) -> {
            out.append(String.format("%-24s", kind.name()));
            for (Variant v : Variant.values()) out.append(String.format("%-12s", row.getOrDefault(v, "-")));
            out.append('\n');
        });
        out.append("decoys: ").append(cases.stream().filter(Case::decoy).map(outcome::get)
                .collect(Collectors.joining(" "))).append('\n');
        Map<String, Long> totals = outcome.entrySet().stream().filter(e -> !e.getKey().decoy())
                .collect(Collectors.groupingBy(Map.Entry::getValue, java.util.TreeMap::new, Collectors.counting()));
        out.append("values: ").append(totals).append('\n');
        long autoBoxes = saved.stream().filter(f -> f.location().hasBoundingBox() && f.status() == FindingStatus.CONFIRMED).count();
        long personBoxes = saved.stream().filter(f -> f.location().hasBoundingBox() && f.status() == FindingStatus.DETECTED).count();
        long pagesHeld = saved.stream().filter(f -> !f.location().hasBoundingBox()).count();
        out.append("boxes: automatic=").append(autoBoxes).append(" person=").append(personBoxes)
                .append(" unplaced=").append(pagesHeld).append('\n');
        // False alarms: a finding on no value of its page (the page's own value, or anything on a decoy page).
        Map<String, int[]> alarms = new java.util.TreeMap<>();
        for (Finding f : saved) {
            if (!f.location().hasBoundingBox()) continue;
            Case c = cases.get(f.location().pageNumber() - 1);
            int[] at = c.decoy() ? null : locate(pages.get(f.location().pageNumber() - 1).text(), c.value());
            boolean onValue = at != null && f.location().startOffset() < at[1] && at[0] < f.location().endOffset();
            if (onValue) continue;
            int[] n = alarms.computeIfAbsent(f.entityType().name(), k -> new int[2]);
            n[f.status() == FindingStatus.CONFIRMED ? 0 : 1]++;
        }
        out.append("false alarms (automatic/person): ");
        alarms.forEach((kind, n) -> out.append(kind).append('=').append(n[0]).append('/').append(n[1]).append(' '));
        System.out.println(out);
    }

    // ------------------------------------------------------------------------------------------------- wiring

    private void fixture(int pageCount) {
        pkg = DocumentPackage.create(tenantId, PackageType.MANUAL_UPLOAD, ActorId.of(UUID.randomUUID()),
                PackageMetadata.empty());
        document = Document.reconstitute(DocumentId.of(UUID.randomUUID()), tenantId, pkg.id(),
                FileName.of("hard-set.pdf"), MimeType.pdf(), source.length, Checksum.sha256("d"), DocumentType.PDF,
                Instant.now(), DocumentStatus.DETECTING,
                new ArrayList<>(IntStream.rangeClosed(1, pageCount).mapToObj(n -> Page.of(n, 612, 792)).toList()),
                Instant.now());
        pkg.addDocument(document);
        pkg.submit();
        pkg.startProcessing();
        assertEquals(PackageStatus.PROCESSING, pkg.status());
        original = Artifact.create(tenantId, document.id(), ArtifactType.ORIGINAL, MimeType.pdf(), source.length,
                Checksum.sha256("o"));
        original.markAvailable(ArtifactStorageReference.local("hard-set.pdf"));
    }

    /** Every kind found in text, on. */
    private TenantRedactionPolicy policy() {
        Set<EntityType> all = RedactionCatalog.entries().stream()
                .filter(e -> e.detection() != RedactionCatalog.DetectionMethod.IMAGE
                        && e.detection() != RedactionCatalog.DetectionMethod.AI)
                .map(RedactionCatalog.Entry::kind).collect(Collectors.toCollection(() -> EnumSet.noneOf(EntityType.class)));
        return new TenantRedactionPolicy(tenantId, all, 0.80, ReviewMode.UNCERTAIN_ONLY, true, Instant.now());
    }

    private final class Findings implements FindingRepository {
        @Override public void save(Finding finding) { saved.add(finding); }
        @Override public void saveAll(List<Finding> findings) { saved.addAll(findings); }
        @Override public Optional<Finding> findByIdAndTenantId(FindingId id, TenantId tenant) { return Optional.empty(); }
        @Override public List<Finding> findByDocumentIdAndTenantId(DocumentId documentId, TenantId tenant) { return List.of(); }
        @Override public void deleteByDocumentIdAndTenantId(DocumentId documentId, TenantId tenant) { }
    }

    private final class Policies implements TenantRedactionPolicyRepository {
        @Override public Optional<TenantRedactionPolicy> findByTenantId(TenantId tenant) { return Optional.of(policy()); }
        @Override public void save(TenantRedactionPolicy policy) { }
    }

    private final class Documents implements DocumentRepository {
        @Override public void save(Document d) { }
        @Override public Optional<Document> findByIdAndTenantId(DocumentId id, TenantId tenant) {
            return document.id().equals(id) ? Optional.of(document) : Optional.empty();
        }
        @Override public List<Document> findByPackageIdAndTenantId(PackageId packageId, TenantId tenant) {
            return List.of(document);
        }
    }

    private final class Packages implements PackageRepository {
        @Override public void save(DocumentPackage documentPackage) { }
        @Override public Optional<DocumentPackage> findByIdAndTenantId(PackageId id, TenantId tenant) {
            return pkg.id().equals(id) ? Optional.of(pkg) : Optional.empty();
        }
        @Override public List<DocumentPackage> findByTenantId(TenantId tenant, int offset, int limit) { return List.of(pkg); }
        @Override public long countByTenantId(TenantId tenant) { return 1; }
        @Override public Optional<DocumentPackage> findByTenantIdAndIntakeFingerprint(TenantId tenant, String fingerprint) {
            return Optional.empty();
        }
        @Override public List<DocumentPackage> findByStatus(PackageStatus status, int limit) { return List.of(); }
    }

    private final class Artifacts implements ArtifactRepository {
        @Override public void save(Artifact artifact) { }
        @Override public Optional<Artifact> findByIdAndTenantId(ArtifactId id, TenantId tenant) { return Optional.of(original); }
        @Override public List<Artifact> findByDocumentIdAndTenantId(DocumentId documentId, TenantId tenant) {
            return List.of(original);
        }
    }

    private final class Storage implements StoragePort {
        @Override public ArtifactStorageReference store(TenantId tenant, String path, InputStream content,
                                                        long sizeBytes, String mimeType) {
            throw new UnsupportedOperationException();
        }
        @Override public InputStream retrieve(ArtifactStorageReference reference) { return new ByteArrayInputStream(source); }
        @Override public void delete(ArtifactStorageReference reference) { }
    }
}
