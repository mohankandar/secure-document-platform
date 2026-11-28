package com.securedoc.platform.application.secondcheck;

import com.securedoc.platform.application.detection.service.VisionFindings;
import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.port.out.PageImagePort;
import com.securedoc.platform.application.port.out.PdfRedactionPort.RedactionTarget;
import com.securedoc.platform.application.port.out.RedactionVerificationPort.RedactedValues;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.DiscoveredSensitiveContent;
import com.securedoc.platform.application.port.out.TextExtractionPort;
import com.securedoc.platform.application.port.out.VisionModelPort;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.finding.AiModel;
import com.securedoc.platform.domain.finding.DetectionPath;
import com.securedoc.platform.domain.finding.DetectorSource;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.finding.FindingLocation;
import com.securedoc.platform.domain.finding.FindingLocation.BoundingBox;
import com.securedoc.platform.domain.finding.FindingOrigin;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.vision.VisionPageResult;
import com.securedoc.platform.domain.vision.VisionPageResult.Kind;
import com.securedoc.platform.domain.vo.Confidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Task 0083: a second, independent AI reading of the finished (redacted) file, with prompts of its own, for anything
 * sensitive that was never found. What it finds is never redacted by itself: the page goes to a person with the kinds
 * only. Values live in memory for this call; none is saved, logged or shown.
 *
 * <ul>
 *   <li>The text model reads every page's text; the vision model reads the image of a page with a redaction, of a page
 *       without text of its own and of a page read by OCR (with the entity's vision switch on).</li>
 *   <li>What points at a cover, at digits that are kept on purpose, or at what a person decided to keep is no hit.</li>
 *   <li>A page whose answer can't be used goes to a person. A model that can't be asked stops the document.</li>
 * </ul>
 */
public final class SecondCheck {
    private static final Logger log = LoggerFactory.getLogger(SecondCheck.class);

    /** The detector name of a page the second check sends to a person; its version holds the kinds. */
    public static final String SOURCE = "second-check";
    /** Short names for the kinds: a finding stores them in 50 characters. Never rename one. */
    private static final Map<Kind, String> CODES = codes();
    private static final String UNREAD = "UNREAD";

    /** At least this share of a box is black: the model pointed at a cover. */
    private static final double COVERED = 0.35;
    private static final int BLACK = 12;
    private static final int LITTLE_TEXT = 20;
    private static final int BIRTH_LABEL_DISTANCE = 80;
    private static final double SAME_PLACE = 0.3;
    private static final int MAX_PAGES_NAMED = 20;
    private static final Pattern BIRTH = Pattern.compile("birth|d.?o.?b|born", Pattern.CASE_INSENSITIVE);
    private static final Pattern RANGE = Pattern.compile("\\b\\d{3}\\s*(?:-|\\x{2013}|to)\\s*\\d{3}\\b");
    private static final Pattern SCORE = Pattern.compile("(?<![\\d.,-])[3-8]\\d\\d(?![\\d.,-])");
    private static final Pattern ONE_SCORE = Pattern.compile("\\D*[3-8]\\d\\d\\D*");

    private final TextExtractionPort extraction;
    private final SensitiveContentAnalysisPort textModel;
    private final PageImagePort images;
    private final VisionModelPort visionModel;

    public SecondCheck(TextExtractionPort extraction, SensitiveContentAnalysisPort textModel, PageImagePort images,
                       VisionModelPort visionModel) {
        this.extraction = Objects.requireNonNull(extraction);
        this.textModel = Objects.requireNonNull(textModel);
        this.images = Objects.requireNonNull(images);
        this.visionModel = Objects.requireNonNull(visionModel);
    }

    /** Opens the finished file. */
    @FunctionalInterface
    public interface Finished {
        InputStream open() throws IOException;
    }

    /** @param pagesToCheck one per page, unlocated, with its kinds; never a value */
    public record Result(int pagesChecked, int pagesSkipped, List<Finding> pagesToCheck, Set<AiModel> models) {
        public String auditDetail() {
            return "Second check: pages=" + pagesChecked + ", checkedByAPersonBefore=" + pagesSkipped
                    + ", pagesToCheck=" + pagesToCheck.size()
                    + (pagesToCheck.isEmpty() ? "" : ", found=[" + found() + "]")
                    + (models.isEmpty() ? "" : ", aiModels=[" + FindingOrigin.encodeModels(models) + "]");
        }

        private String found() {
            return pagesToCheck.stream().limit(MAX_PAGES_NAMED).map(f -> "p" + f.location().pageNumber() + ":"
                    + String.join("+", kindsOf(f))).collect(Collectors.joining(" "));
        }
    }

    /**
     * The kinds a page was sent to a person for; null when the second check did not send it. A page whose answer could
     * not be used says UNREAD.
     */
    public static List<String> kindsOf(Finding finding) {
        if (finding.entityType() != EntityType.UNREAD_PAGE || finding.detectorSource() == null
                || !SOURCE.equals(finding.detectorSource().detectorName())) return null;
        return List.of(finding.detectorSource().version().split(","));
    }

    /**
     * @param dismissed    what a person decided to keep visible, and the pages a person checked
     * @param keptByPerson the text of those that have one; in memory only
     */
    public Result run(TenantId tenantId, DocumentId documentId, TenantRedactionPolicy policy, Finished finished,
                      List<RedactionTarget> targets, List<Finding> dismissed, List<String> keptByPerson) {
        Set<Integer> checkedByAPerson = dismissed.stream()
                .filter(f -> f.entityType() == EntityType.UNREAD_PAGE && !f.location().hasBoundingBox())
                .map(f -> f.location().pageNumber()).collect(Collectors.toSet());
        return run(tenantId, documentId, policy, finished, targets, dismissed, keptByPerson, checkedByAPerson, null);
    }

    /**
     * Task 0119: the pages a person finished in this case are not read again; {@code readings} are the vision readings
     * of this file shared with the final check (null: none shared), so a picture page is read once.
     */
    public Result run(TenantId tenantId, DocumentId documentId, TenantRedactionPolicy policy, Finished finished,
                      List<RedactionTarget> targets, List<Finding> dismissed, List<String> keptByPerson,
                      Set<Integer> checkedByAPerson, Map<Integer, VisionPageResult> readings) {
        long started = System.nanoTime();
        List<TextExtractionPort.PageText> pages;
        int finishedByAPerson = 0;
        try (InputStream in = finished.open()) {
            if (checkedByAPerson.isEmpty()) {
                pages = extraction.extractReadable(in, "application/pdf", null).pages();
            } else {
                // Task 0119: no OCR of the pages a person finished.
                Set<Integer> notChecked = new java.util.TreeSet<>();
                for (TextExtractionPort.PageText page : extraction.extractTextLayer(in, "application/pdf").pages()) {
                    if (checkedByAPerson.contains(page.pageNumber())) finishedByAPerson++;
                    else notChecked.add(page.pageNumber());
                }
                if (notChecked.isEmpty()) {
                    pages = List.of();
                } else {
                    try (InputStream again = finished.open()) {
                        pages = extraction.extractReadable(again, "application/pdf", notChecked).pages();
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("The finished file could not be read for the second check");
        }
        VisionModelPort visionModel = sharing(this.visionModel, readings);
        Set<String> kept = keptByPerson.stream().map(RedactedValues::comparable)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Integer> redacted = targets.stream().map(RedactionTarget::pageNumber).collect(Collectors.toSet());

        Set<AiModel> models = new LinkedHashSet<>();
        Map<Integer, Set<String>> hits = new TreeMap<>();
        int checked = 0;
        int skipped = finishedByAPerson;
        for (TextExtractionPort.PageText page : pages) {
            if (checkedByAPerson.contains(page.pageNumber())) {
                skipped++;
                continue;
            }
            checked++;
            Set<String> found = new LinkedHashSet<>(readText(tenantId, page, policy, targets, kept, models));
            boolean byImage = policy.vision().enabled() && (redacted.contains(page.pageNumber())
                    || page.source() == TextExtractionPort.ExtractionSource.OCR
                    || page.text().strip().length() < LITTLE_TEXT);
            if (byImage) found.addAll(readImage(images, visionModel, page.pageNumber(), page.text(), policy, finished,
                    targets, dismissed, models));
            if (!found.isEmpty()) hits.put(page.pageNumber(), found);
        }
        List<AiModel> tookPart = List.copyOf(models);
        List<Finding> toCheck = new ArrayList<>();
        hits.forEach((page, found) -> toCheck.add(Finding.create(tenantId, documentId, EntityType.UNREAD_PAGE,
                FindingLocation.unlocated(page), Confidence.of(0.0), new DetectorSource(SOURCE, inOrder(found)),
                FindingOrigin.of(DetectionPath.PAGE_CHECK, tookPart))));
        // Counts only.
        log.info("SECOND_CHECK_COMPLETED document={} pages={} skipped={} pagesToCheck={} durationMs={}", documentId,
                checked, skipped, toCheck.size(), (System.nanoTime() - started) / 1_000_000L);
        return new Result(checked, skipped, List.copyOf(toCheck), models);
    }

    /**
     * Task 0119: the model, asked once per page of one file: a page another check of the same file already read is
     * answered from {@code readings}. Only a page that was read is kept. Null readings: the model itself.
     */
    static VisionModelPort sharing(VisionModelPort model, Map<Integer, VisionPageResult> readings) {
        if (readings == null) return model;
        return (image, page) -> {
            VisionPageResult had = readings.get(page);
            if (had != null) return had;
            VisionPageResult result = model.read(image, page);
            if (result.read()) readings.put(page, result);
            return result;
        };
    }

    private Set<String> readText(TenantId tenantId, TextExtractionPort.PageText page, TenantRedactionPolicy policy,
                                 List<RedactionTarget> targets, Set<String> kept, Set<AiModel> models) {
        if (page.text().isBlank()) return Set.of();
        SensitiveContentAnalysisPort.AnalysisOutput output;
        try {
            output = textModel.analyze(new SensitiveContentAnalysisPort.AnalysisRequest(tenantId,
                    List.of(new SensitiveContentAnalysisPort.PageContent(page.pageNumber(), page.text())),
                    SensitiveContentAnalysisPort.AnalysisPolicy.ofSecondCheck()));
        } catch (TechnicalDependencyException failure) {
            if (failure.failureCode() == TechnicalFailureCode.AI_INVALID_RESPONSE) return Set.of(UNREAD);
            throw failure;
        }
        models.addAll(output.models());
        Set<String> found = new LinkedHashSet<>();
        for (DiscoveredSensitiveContent item : output.findings()) {
            Kind kind = kind(item.entityType());
            if (!CODES.containsKey(kind) || VisionFindings.type(kind, policy.enabledEntityTypes()) == null) continue;
            String compared = RedactedValues.comparable(item.matchedText());
            boolean onPurpose = kept.contains(compared)
                    || targets.stream().anyMatch(t -> t.kept() != null && t.kept().are(compared));
            if (!onPurpose && plausible(kind, item.matchedText(), page.text())) found.add(CODES.get(kind));
        }
        return found;
    }


    /** The short name a page of the final check gets when the vision model's answer can't be used. */
    static final String UNREAD_CODE = UNREAD;

    /**
     * Task 0103: also the final check's reading of a page that was a picture in the original (the owner's run 2: OCR
     * misread two handwritten dates, so the pattern search of the finished file could not see them either). The model's
     * findings on the finished page image, less what points at a cover, at digits kept on purpose or at what a person
     * kept. A model that can't be asked throws, as in the second check.
     */
    static Set<String> readImage(PageImagePort images, VisionModelPort visionModel, int number, String text,
                                 TenantRedactionPolicy policy, Finished finished, List<RedactionTarget> targets,
                                 List<Finding> dismissed, Set<AiModel> models) {
        PageImagePort.PageImage image;
        try (InputStream in = finished.open()) {
            image = images.render(in, number, VisionModelPort.IMAGE_LONG_SIDE);
        } catch (IOException | RuntimeException notRendered) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("The second check was interrupted");
            }
            return Set.of(UNREAD);
        }
        VisionPageResult result = visionModel.read(image, number);
        if (!result.read()) {
            if (LvErrorCode.AI_005.code().equals(result.code())) return Set.of(UNREAD);
            throw new TechnicalDependencyException(TechnicalDependency.AI, failure(result.code()), false,
                    "The second check's vision model could not be asked");
        }
        models.add(result.model());
        // Task 0092: a check's line that is still to be seen, whatever the model says.
        List<VisionPageResult.Item> items = new ArrayList<>(result.items());
        try (InputStream in = finished.open()) {
            for (PageImagePort.CheckLine line : images.checkLines(in, number)) {
                items.add(new VisionPageResult.Item(Kind.ROUTING_NUMBER, line.routing(), true));
                items.add(new VisionPageResult.Item(Kind.BANK_ACCOUNT, line.account(), true));
            }
        } catch (IOException | RuntimeException notSearched) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("The second check was interrupted");
            }
            return Set.of(UNREAD);
        }
        Set<String> found = new LinkedHashSet<>();
        if (result.incomplete()) found.add(UNREAD);
        BufferedImage pixels = null;
        for (VisionPageResult.Item item : items) {
            EntityType type = CODES.containsKey(item.kind())
                    ? VisionFindings.type(item.kind(), policy.enabledEntityTypes()) : null;
            if (type == null || (item.kind() == Kind.CREDIT_SCORE && !scoreIn(text))) continue;
            if (keptDigits(targets, number, item.box()) || keptByAPerson(dismissed, number, type, item.box())) continue;
            if (pixels == null) pixels = decode(image);
            if (pixels == null) return Set.of(UNREAD);
            if (black(pixels, item.box()) < COVERED) found.add(CODES.get(item.kind()));
        }
        return found;
    }

    // ------------------------------------------------------------------------------------------------ what counts

    /** The shape of its kind; a date of birth also has a birth label close before it. */
    static boolean plausible(Kind kind, String value, String page) {
        long digits = value.chars().filter(c -> c >= '0' && c <= '9').count();
        return switch (kind) {
            case SSN, ROUTING_NUMBER -> digits == 9;
            case DATE_OF_BIRTH -> digits >= 5 && born(value, page);
            case CREDIT_CARD_NUMBER -> digits >= 13 && digits <= 19;
            case CREDIT_SCORE -> ONE_SCORE.matcher(value).matches();
            case BANK_ACCOUNT -> digits >= 4 && digits <= 17;
            case GOVERNMENT_ID -> digits >= 5;
            case SIGNATURE, FACE_PHOTO -> false;
        };
    }

    /** At any place the value stands, not only the first. */
    private static boolean born(String value, String page) {
        for (int at = page.indexOf(value); at >= 0; at = page.indexOf(value, at + 1)) {
            if (BIRTH.matcher(page.substring(Math.max(0, at - BIRTH_LABEL_DISTANCE), at)).find()) return true;
        }
        return false;
    }

    /** A range such as 300-850 is not a score; with too little text to tell, the model is believed. */
    static boolean scoreIn(String text) {
        if (text.strip().length() < LITTLE_TEXT) return true;
        return text.toLowerCase(Locale.ROOT).contains("score")
                && SCORE.matcher(RANGE.matcher(text).replaceAll(" ")).find();
    }

    private static boolean keptDigits(List<RedactionTarget> targets, int page, BoundingBox box) {
        RedactionTarget hit = RedactionTarget.of(page, box.left(), box.top(), box.width(), box.height());
        return targets.stream().anyMatch(t -> t.kept() != null && t.intersects(hit));
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

    private static BufferedImage decode(PageImagePort.PageImage image) {
        try {
            return ImageIO.read(new ByteArrayInputStream(image.png()));
        } catch (IOException e) {
            return null;
        }
    }

    private static double black(BufferedImage image, BoundingBox box) {
        int x1 = (int) Math.round(box.left() * image.getWidth());
        int x2 = (int) Math.min(image.getWidth(), Math.round((box.left() + box.width()) * image.getWidth()));
        int y1 = (int) Math.round(box.top() * image.getHeight());
        int y2 = (int) Math.min(image.getHeight(), Math.round((box.top() + box.height()) * image.getHeight()));
        long all = 0;
        long dark = 0;
        for (int y = y1; y < y2; y++) {
            for (int x = x1; x < x2; x++) {
                int rgb = image.getRGB(x, y);
                all++;
                if ((rgb >> 16 & 0xFF) < BLACK && (rgb >> 8 & 0xFF) < BLACK && (rgb & 0xFF) < BLACK) dark++;
            }
        }
        // Too small to judge: believed, for a person to look at.
        return all == 0 ? 0 : (double) dark / all;
    }

    private static Kind kind(String name) {
        try {
            return Kind.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return Kind.SIGNATURE; // not one of the second check's kinds
        }
    }

    private static TechnicalFailureCode failure(String code) {
        if (LvErrorCode.AI_001.code().equals(code)) return TechnicalFailureCode.AI_UNAVAILABLE;
        if (LvErrorCode.AI_002.code().equals(code)) return TechnicalFailureCode.AI_TIMEOUT;
        if (LvErrorCode.AI_003.code().equals(code)) return TechnicalFailureCode.AI_RATE_LIMITED;
        return TechnicalFailureCode.AI_REJECTED;
    }

    private static String inOrder(Set<String> found) {
        List<String> ordered = new ArrayList<>(CODES.values());
        ordered.add(UNREAD);
        ordered.retainAll(found);
        return String.join(",", ordered);
    }

    private static Map<Kind, String> codes() {
        Map<Kind, String> codes = new LinkedHashMap<>();
        codes.put(Kind.SSN, "SSN");
        codes.put(Kind.DATE_OF_BIRTH, "DOB");
        codes.put(Kind.CREDIT_CARD_NUMBER, "CARD");
        codes.put(Kind.CREDIT_SCORE, "SCORE");
        codes.put(Kind.BANK_ACCOUNT, "ACCOUNT");
        codes.put(Kind.ROUTING_NUMBER, "ROUTING");
        codes.put(Kind.GOVERNMENT_ID, "ID");
        codes.put(Kind.FACE_PHOTO, "FACE");
        return codes;
    }
}
