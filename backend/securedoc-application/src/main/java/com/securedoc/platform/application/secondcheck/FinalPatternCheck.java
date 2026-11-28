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

    /** Task 0103: null where vision is not wired. */
    private com.securedoc.platform.application.port.out.PageRouteRepository routes;
    private com.securedoc.platform.application.port.out.PageImagePort images;
    private com.securedoc.platform.application.port.out.VisionModelPort vision;

    /**
     * Task 0103 (owner's run 2): the pages that were a picture in the original (read by OCR or by the vision model)
     * are also read by the vision model in the finished file, when the entity's vision switch is on. OCR can misread
     * handwriting, so the pattern search alone can't see what is left there.
     */
    public FinalPatternCheck withVision(com.securedoc.platform.application.port.out.PageRouteRepository pageRoutes,
                                        com.securedoc.platform.application.port.out.PageImagePort pageImages,
                                        com.securedoc.platform.application.port.out.VisionModelPort model) {
        this.routes = Objects.requireNonNull(pageRoutes);
        this.images = Objects.requireNonNull(pageImages);
        this.vision = Objects.requireNonNull(model);
        return this;
    }

    /**
     * What the vision model still sees on the finished page; UNREAD when its answer can't be used or it can't be asked.
     * Task 0119: with {@code readings}, a page the second check's vision reading already read is not asked again.
     */
    private Set<String> readFinishedPage(int page, String text, TenantRedactionPolicy policy, SecondCheck.Finished finished,
                                         List<RedactionTarget> targets, List<Finding> dismissed,
                                         Map<Integer, com.securedoc.platform.domain.vision.VisionPageResult> readings) {
        try {
            return SecondCheck.readImage(images, SecondCheck.sharing(vision, readings), page, text, policy, finished,
                    targets, dismissed, new LinkedHashSet<>());
        } catch (RuntimeException notAsked) {
            if (Thread.currentThread().isInterrupted()) throw notAsked;
            // The model could not be asked: the page is not delivered on the text search alone.
            return Set.of(SecondCheck.UNREAD_CODE);
        }
    }

    /** The pages that were pictures in the original: routed to vision, or read by OCR for routing. */
    private Set<Integer> picturePages(TenantId tenantId, DocumentId documentId) {
        if (routes == null) return Set.of();
        return routes.findByDocument(tenantId, documentId).stream()
                .filter(r -> r.vision() || r.ocrConfidence() != null)
                .map(com.securedoc.platform.domain.vision.PageRoute::page)
                .collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    /**
     * Task 0119: when the check runs. {@code BEFORE_REVIEW}: before a case's first stop for a person, on a trial file in
     * which the findings still waiting for a person are covered as proposed, so that every item for a page is shown in
     * one review. {@code BEFORE_KEEPING}: on the finished file, before it is kept.
     */
    public enum Moment { BEFORE_REVIEW, BEFORE_KEEPING }

    /**
     * @param pagesToCheck       one per page, unlocated, with its kinds; never a value
     * @param leftOnCheckedPages task 0119: pages a person finished on which a value is still found; our defect
     * @param pagesReread        task 0119: pages read again with OCR (and the vision model where it reads them)
     */
    public record Result(int pagesChecked, int pagesSkipped, List<Finding> pagesToCheck,
                         Set<Integer> leftOnCheckedPages, int pagesReread) {
        public Result(int pagesChecked, int pagesSkipped, List<Finding> pagesToCheck) {
            this(pagesChecked, pagesSkipped, pagesToCheck, Set.of(), 0);
        }

        public Result {
            pagesToCheck = List.copyOf(pagesToCheck);
            leftOnCheckedPages = Set.copyOf(leftOnCheckedPages);
        }

        public String auditDetail() {
            return "Final check: pages=" + pagesChecked + ", checkedByAPersonBefore=" + pagesSkipped
                    + ", pagesToCheck=" + pagesToCheck.size()
                    + (pagesToCheck.isEmpty() ? "" : ", found=[" + pagesToCheck.stream().limit(MAX_PAGES_NAMED)
                            .map(f -> "p" + f.location().pageNumber() + ":" + f.detectorSource().version()
                                    .replace(',', '+'))
                            .collect(Collectors.joining(" ")) + "]")
                    + ", readAgain=" + pagesReread
                    + (leftOnCheckedPages.isEmpty() ? "" : ", leftOnCheckedPages=[" + leftOnCheckedPages.stream()
                            .sorted().limit(MAX_PAGES_NAMED).map(p -> "p" + p).collect(Collectors.joining(" ")) + "]");
        }
    }

    /** The kinds a page was sent to a person for; null when the final check did not send it. */
    public static List<String> kindsOf(Finding finding) {
        if (finding.entityType() != EntityType.UNREAD_PAGE || finding.detectorSource() == null
                || !SOURCE.equals(finding.detectorSource().detectorName())) return null;
        return List.of(finding.detectorSource().version().split(","));
    }

    /**
     * As before task 0119: the pages a person checked are those of a dismissed page check; on the finished file.
     *
     * @param dismissed    what a person decided to keep visible, and the pages a person checked
     * @param keptByPerson the text of those that have one; in memory only
     */
    public Result run(TenantId tenantId, DocumentId documentId, TenantRedactionPolicy policy,
                      SecondCheck.Finished finished, List<RedactionTarget> targets, List<Finding> dismissed,
                      List<String> keptByPerson) {
        Set<Integer> checked = dismissed.stream()
                .filter(f -> f.entityType() == EntityType.UNREAD_PAGE && !f.location().hasBoundingBox())
                .map(f -> f.location().pageNumber()).collect(Collectors.toSet());
        return run(tenantId, documentId, policy, finished, targets, dismissed, keptByPerson, checked, List.of(),
                Moment.BEFORE_KEEPING, null);
    }

    /**
     * Task 0119 (owner, 2026-11-28): (a) the quick search of every page's stored text, always; (b) where the entity has
     * it on (default), OCR and the vision model read again the picture pages no person looked at in this case; (c) the
     * pages a person finished are never read again. A value the quick search still finds on a page a person finished
     * is our defect: the page is named in {@link Result#leftOnCheckedPages()} (the document stops), never sent back.
     *
     * @param seenByAPerson the pages a person finished in this case (before review: also the pages a person will see)
     * @param waiting       before review: the findings still waiting for a person (an unlocated one is no hit again)
     * @param readings      the vision readings of this file, shared with the second check; null: none shared
     */
    public Result run(TenantId tenantId, DocumentId documentId, TenantRedactionPolicy policy,
                      SecondCheck.Finished finished, List<RedactionTarget> targets, List<Finding> dismissed,
                      List<String> keptByPerson, Set<Integer> seenByAPerson, List<Finding> waiting, Moment moment,
                      Map<Integer, com.securedoc.platform.domain.vision.VisionPageResult> readings) {
        long started = System.nanoTime();
        List<TextExtractionPort.PageText> stored;
        try (InputStream in = finished.open()) {
            stored = extraction.extractTextLayer(in, "application/pdf").pages();
        } catch (IOException e) {
            throw new IllegalStateException("The finished file could not be read for the final check");
        }
        Set<String> kept = keptByPerson.stream().map(RedactedValues::comparable)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // (a) Every page's stored text.
        Map<Integer, Set<String>> hits = search(stored, policy, targets, dismissed, kept);

        // (b) Picture pages no person looked at: OCR again, and the vision model where it reads them.
        Set<Integer> pictures = picturePages(tenantId, documentId);
        Set<Integer> reread = new java.util.TreeSet<>();
        if (policy.finalCheckReread()) {
            for (TextExtractionPort.PageText page : stored) {
                boolean picture = pictures.contains(page.pageNumber()) || page.text().strip().length() < LITTLE_TEXT;
                if (picture && !seenByAPerson.contains(page.pageNumber())) reread.add(page.pageNumber());
            }
        }
        int visionPages = 0;
        if (!reread.isEmpty()) {
            List<TextExtractionPort.PageText> read;
            try (InputStream in = finished.open()) {
                read = extraction.extractReadable(in, "application/pdf", reread).pages();
            } catch (IOException e) {
                throw new IllegalStateException("The finished file could not be read for the final check");
            }
            search(read, policy, targets, dismissed, kept)
                    .forEach((page, kinds) -> hits.computeIfAbsent(page, p -> new LinkedHashSet<>()).addAll(kinds));
            boolean vision = this.vision != null && policy.vision().enabled();
            for (TextExtractionPort.PageText page : read) {
                if (!vision || !pictures.contains(page.pageNumber())) continue;
                visionPages++;
                Set<String> seen = readFinishedPage(page.pageNumber(), page.text(), policy, finished, targets,
                        dismissed, readings);
                // The model now and then gives an answer that can't be used (seen on handwritten pages); asked once more.
                if (seen.equals(Set.of(SecondCheck.UNREAD_CODE))) {
                    seen = readFinishedPage(page.pageNumber(), page.text(), policy, finished, targets, dismissed, null);
                }
                if (!seen.isEmpty()) hits.computeIfAbsent(page.pageNumber(), p -> new LinkedHashSet<>()).addAll(seen);
            }
        }

        List<Finding> toCheck = new ArrayList<>();
        Set<Integer> leftOnChecked = new java.util.TreeSet<>();
        hits.forEach((page, kinds) -> {
            // The kinds a person was shown on a page check of this page and let stand are kept, as a dismissed value.
            dismissed.stream().filter(f -> f.location().pageNumber() == page && !f.location().hasBoundingBox())
                    .map(f -> kindsOf(f) != null ? kindsOf(f) : SecondCheck.kindsOf(f)).filter(Objects::nonNull)
                    .forEach(kinds::removeAll);
            if (kinds.isEmpty()) return;
            if (moment == Moment.BEFORE_REVIEW) {
                // A value a waiting finding has no place for yet is shown with that finding already.
                waiting.stream().filter(f -> f.location().pageNumber() == page && !f.location().hasBoundingBox())
                        .map(f -> CODES.get(f.entityType())).filter(Objects::nonNull).forEach(kinds::remove);
                if (kinds.isEmpty()) return;
            } else if (seenByAPerson.contains(page)) {
                leftOnChecked.add(page);
                return;
            }
            toCheck.add(Finding.create(tenantId, documentId, EntityType.UNREAD_PAGE, FindingLocation.unlocated(page),
                    Confidence.of(0.0), new DetectorSource(SOURCE, inOrder(kinds)),
                    FindingOrigin.of(DetectionPath.PAGE_CHECK, List.of())));
        });
        int skipped = (int) stored.stream().filter(p -> seenByAPerson.contains(p.pageNumber())).count();
        // Counts only.
        log.info("FINAL_CHECK_COMPLETED document={} moment={} pages={} checkedByAPerson={} readAgain={} visionPages={} "
                        + "pagesToCheck={} leftOnCheckedPages={} durationMs={}", documentId, moment, stored.size(),
                skipped, reread.size(), visionPages, toCheck.size(), leftOnChecked.size(),
                (System.nanoTime() - started) / 1_000_000L);
        return new Result(stored.size(), skipped, toCheck, leftOnChecked, reread.size());
    }

    /** Less than this much stored text: the page is a picture in the finished file (a redacted page is one). */
    private static final int LITTLE_TEXT = 20;

    /** The pattern rules on these pages: the kinds still found per page, less what is kept on purpose. */
    private Map<Integer, Set<String>> search(List<TextExtractionPort.PageText> pages, TenantRedactionPolicy policy,
                                             List<RedactionTarget> targets, List<Finding> dismissed, Set<String> kept) {
        Map<Integer, TextExtractionPort.PageText> byNumber = new LinkedHashMap<>();
        List<SensitiveDataDetectionPort.PageInput> inputs = new ArrayList<>();
        for (TextExtractionPort.PageText page : pages) {
            byNumber.put(page.pageNumber(), page);
            if (!page.text().isBlank()) {
                inputs.add(new SensitiveDataDetectionPort.PageInput(page.pageNumber(), page.text()));
            }
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
        return hits;
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
        // Task 0103: what the vision model can name beyond the patterns, and a page it could not read.
        ordered.add("FACE");
        ordered.add(SecondCheck.UNREAD_CODE);
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
