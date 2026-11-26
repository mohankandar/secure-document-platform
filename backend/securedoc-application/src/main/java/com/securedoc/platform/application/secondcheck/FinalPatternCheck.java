package com.securedoc.platform.application.secondcheck;

import com.securedoc.platform.application.detection.service.FindingLocationMapper;
import com.securedoc.platform.application.port.out.PdfRedactionPort.RedactionTarget;
import com.securedoc.platform.application.port.out.RedactionVerificationPort.RedactedValues;
import com.securedoc.platform.application.port.out.SensitiveDataDetectionPort;
import com.securedoc.platform.application.port.out.TextExtractionPort;
import com.securedoc.platform.domain.business.RedactionCatalog;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.finding.DetectionPath;
import com.securedoc.platform.domain.finding.DetectorSource;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.finding.FindingLocation;
import com.securedoc.platform.domain.finding.FindingLocation.BoundingBox;
import com.securedoc.platform.domain.finding.FindingOrigin;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.vo.Confidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Task 0099: the guard before anything is kept for delivery. The finished file is read as a reader of it would read it
 * (each page's text, with OCR where a page is a picture and the text OCR doesn't see) and searched with the pattern
 * rules again. A locked kind, or a kind the entity has on, still found there stops delivery: the page goes to Review
 * with the kinds. Not switchable. What a person decided to keep visible, and digits kept on purpose, are no hit.
 * Values live in memory for this call; none is saved, logged or shown.
 */
public final class FinalPatternCheck {
    private static final Logger log = LoggerFactory.getLogger(FinalPatternCheck.class);

    /** The detector name of a page the final check sends to a person; its version holds the kinds. */
    public static final String SOURCE = "final-check";
    /** Short names for the kinds: a finding stores them in 50 characters. Never rename one. */
    private static final Map<EntityType, String> CODES = codes();
    private static final double SAME_PLACE = 0.3;
    private static final int MAX_PAGES_NAMED = 20;

    private final TextExtractionPort extraction;
    private final SensitiveDataDetectionPort patterns;

    public FinalPatternCheck(TextExtractionPort extraction, SensitiveDataDetectionPort patterns) {
        this.extraction = Objects.requireNonNull(extraction);
        this.patterns = Objects.requireNonNull(patterns);
    }

    /** @param pagesToCheck one per page, unlocated, with its kinds; never a value */
    public record Result(int pagesChecked, int pagesSkipped, List<Finding> pagesToCheck) {
        public String auditDetail() {
            return "Final check: pages=" + pagesChecked + ", checkedByAPersonBefore=" + pagesSkipped
                    + ", pagesToCheck=" + pagesToCheck.size()
                    + (pagesToCheck.isEmpty() ? "" : ", found=[" + pagesToCheck.stream().limit(MAX_PAGES_NAMED)
                            .map(f -> "p" + f.location().pageNumber() + ":" + f.detectorSource().version()
                                    .replace(',', '+'))
                            .collect(Collectors.joining(" ")) + "]");
        }
    }

    /** The kinds a page was sent to a person for; null when the final check did not send it. */
    public static List<String> kindsOf(Finding finding) {
        if (finding.entityType() != EntityType.UNREAD_PAGE || finding.detectorSource() == null
                || !SOURCE.equals(finding.detectorSource().detectorName())) return null;
        return List.of(finding.detectorSource().version().split(","));
    }

    /**
     * @param dismissed    what a person decided to keep visible, and the pages a person checked
     * @param keptByPerson the text of those that have one; in memory only
     */
    public Result run(TenantId tenantId, DocumentId documentId, TenantRedactionPolicy policy,
                      SecondCheck.Finished finished, List<RedactionTarget> targets, List<Finding> dismissed,
                      List<String> keptByPerson) {
        long started = System.nanoTime();
        List<TextExtractionPort.PageText> pages;
        try (InputStream in = finished.open()) {
            pages = extraction.extractReadable(in, "application/pdf", null).pages();
        } catch (IOException e) {
            throw new IllegalStateException("The finished file could not be read for the final check");
        }
        Set<Integer> checkedByAPerson = dismissed.stream()
                .filter(f -> f.entityType() == EntityType.UNREAD_PAGE && !f.location().hasBoundingBox())
                .map(f -> f.location().pageNumber()).collect(Collectors.toSet());
        Set<String> kept = keptByPerson.stream().map(RedactedValues::comparable)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<Integer, TextExtractionPort.PageText> byNumber = new LinkedHashMap<>();
        List<SensitiveDataDetectionPort.PageInput> inputs = new ArrayList<>();
        int skipped = 0;
        for (TextExtractionPort.PageText page : pages) {
            if (checkedByAPerson.contains(page.pageNumber())) {
                skipped++;
                continue;
            }
            byNumber.put(page.pageNumber(), page);
            inputs.add(new SensitiveDataDetectionPort.PageInput(page.pageNumber(), page.text()));
        }
        Set<EntityType> searched = java.util.EnumSet.noneOf(EntityType.class);
        searched.addAll(policy.enabledEntityTypes());
        searched.addAll(RedactionCatalog.lockedKinds());

        Map<Integer, Set<String>> hits = new TreeMap<>();
        List<SensitiveDataDetectionPort.DetectedEntity> found = inputs.isEmpty() ? List.of()
                : patterns.detect(inputs).entities();
        for (SensitiveDataDetectionPort.DetectedEntity hit : found) {
            EntityType type = type(hit.entityType());
            if (type == null || !searched.contains(type) || !CODES.containsKey(type)) continue;
            TextExtractionPort.PageText page = byNumber.get(hit.pageNumber());
            if (page == null || hit.startOffset() < 0 || hit.endOffset() > page.text().length()) continue;
            String compared = RedactedValues.comparable(page.text().substring(hit.startOffset(), hit.endOffset()));
            if (kept.contains(compared) || targets.stream().anyMatch(t -> t.kept() != null && t.kept().are(compared))) {
                continue;
            }
            Optional<BoundingBox> box = FindingLocationMapper.resolveBoundingBox(page, hit.startOffset(), hit.endOffset());
            if (box.isPresent() && keptByAPerson(dismissed, hit.pageNumber(), type, box.get())) continue;
            hits.computeIfAbsent(hit.pageNumber(), p -> new LinkedHashSet<>()).add(CODES.get(type));
        }
        List<Finding> toCheck = new ArrayList<>();
        hits.forEach((page, kinds) -> toCheck.add(Finding.create(tenantId, documentId, EntityType.UNREAD_PAGE,
                FindingLocation.unlocated(page), Confidence.of(0.0), new DetectorSource(SOURCE, inOrder(kinds)),
                FindingOrigin.of(DetectionPath.PAGE_CHECK, List.of()))));
        // Counts only.
        log.info("FINAL_CHECK_COMPLETED document={} pages={} skipped={} pagesToCheck={} durationMs={}", documentId,
                byNumber.size(), skipped, toCheck.size(), (System.nanoTime() - started) / 1_000_000L);
        return new Result(byNumber.size(), skipped, List.copyOf(toCheck));
    }

    private static boolean keptByAPerson(List<Finding> dismissed, int page, EntityType type, BoundingBox box) {
        return dismissed.stream().anyMatch(f -> f.entityType() == type && f.location().pageNumber() == page
                && f.location().hasBoundingBox() && overlap(f.location().boundingBox(), box) >= SAME_PLACE);
    }

    /** The shared area as a share of the smaller box. */
    private static double overlap(BoundingBox a, BoundingBox b) {
        double w = Math.min(a.left() + a.width(), b.left() + b.width()) - Math.max(a.left(), b.left());
        double h = Math.min(a.top() + a.height(), b.top() + b.height()) - Math.max(a.top(), b.top());
        if (w <= 0 || h <= 0) return 0;
        return w * h / Math.min(a.width() * a.height(), b.width() * b.height());
    }

    private static EntityType type(String name) {
        try {
            return EntityType.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            return null;
        }
    }

    private static String inOrder(Set<String> found) {
        List<String> ordered = new ArrayList<>(new LinkedHashSet<>(CODES.values()));
        ordered.retainAll(found);
        return String.join(",", ordered);
    }

    /** Every catalog kind with a pattern; several ID kinds share one name, as in the second check. */
    private static Map<EntityType, String> codes() {
        Map<EntityType, String> codes = new LinkedHashMap<>();
        codes.put(EntityType.SSN, "SSN");
        codes.put(EntityType.DATE_OF_BIRTH, "DOB");
        codes.put(EntityType.CREDIT_CARD_NUMBER, "CARD");
        codes.put(EntityType.CVV, "CVV");
        codes.put(EntityType.PIN, "PIN");
        codes.put(EntityType.CREDIT_SCORE, "SCORE");
        codes.put(EntityType.BANK_ACCOUNT_NUMBER, "ACCOUNT");
        codes.put(EntityType.BANK_ROUTING_NUMBER, "ROUTING");
        codes.put(EntityType.ITIN, "ID");
        codes.put(EntityType.EIN, "ID");
        codes.put(EntityType.DRIVERS_LICENSE_NUMBER, "ID");
        codes.put(EntityType.PASSPORT_NUMBER, "ID");
        return codes;
    }
}
