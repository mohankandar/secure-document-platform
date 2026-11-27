package com.securedoc.platform.application.detection.service;

import com.securedoc.platform.application.pipeline.PageBatchFiles;
import com.securedoc.platform.application.detection.service.PageBatchFindings.Candidate;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.finding.AiModel;
import com.securedoc.platform.domain.finding.DetectionPath;
import com.securedoc.platform.domain.finding.DetectorSource;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.finding.FindingLocation;
import com.securedoc.platform.domain.finding.FindingLocation.BoundingBox;
import com.securedoc.platform.domain.finding.FindingOrigin;
import com.securedoc.platform.domain.finding.FindingPlacement;
import com.securedoc.platform.domain.finding.FindingPlacement.How;
import com.securedoc.platform.domain.finding.FindingPlacement.Method;
import com.securedoc.platform.domain.finding.FindingPlacement.Reader;
import com.securedoc.platform.domain.vision.PageRoute;
import com.securedoc.platform.domain.vision.VisionPageResult;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Task 0064: the findings of the vision model and the signature detector, and their merging with the text path's by
 * place on the page (one finding per box). Kinds, places and confidence only; never a value.
 */
public final class VisionFindings {

    /**
     * The model states no confidence. A finding only it made gets this one, below the default auto-redact threshold
     * (0.80), so a person sees it first (owner, 0040 answer 2: "a false flag only goes to review").
     */
    static final double VISION_CONFIDENCE = 0.75;
    /** The signature detector: 74 of 74 found with no false flag in the benchmark (report 0033). */
    static final double SIGNATURE_CONFIDENCE = 0.95;
    /** Task 0092: after the kind's name in the file of a vision job. */
    private static final String CHECK_LINE = "+LINE";
    private static final double AGREEMENT_BOOST = 0.05;
    /** Every side of a vision box, as a share of the page: the box must cover the whole value. */
    private static final double MARGIN = 0.004;
    private static final double SAME_KIND_OVERLAP = 0.3;
    private static final double OTHER_KIND_IOU = 0.5;
    /** A box far larger than the other is not the same place: it stays a finding of its own, for a person. */
    private static final double MAX_AREA_RATIO = 10;

    private VisionFindings() {
    }

    /** What the signature detector found on the page, where the entity redacts signatures. */
    static List<Candidate> signatures(PageRoute route, Set<EntityType> enabled) {
        if (!enabled.contains(EntityType.SIGNATURE)) return List.of();
        List<Candidate> found = new ArrayList<>();
        for (BoundingBox box : route.signatures()) {
            found.add(new Candidate(EntityType.SIGNATURE, FindingLocation.withBounds(route.page(), 0, 0, box),
                    SIGNATURE_CONFIDENCE, new DetectorSource("signature-detector", "1"),
                    FindingOrigin.of(DetectionPath.SIGNATURE, List.of()),
                    FindingPlacement.automatic(Reader.SIGNATURE, Method.PATTERN, How.READER_WORDS)));
        }
        return found;
    }

    /** The page's vision findings, of the kinds the entity redacts; a page that needs a person says so. */
    static List<Candidate> of(VisionPageResult result, TenantRedactionPolicy policy) {
        List<Candidate> found = new ArrayList<>();
        for (VisionPageResult.Item item : result.items()) {
            EntityType type = type(item.kind(), policy.enabledEntityTypes());
            if (type == null) continue;
            BoundingBox box = widened(item.box());
            found.add(new Candidate(type, FindingLocation.withBounds(result.page(), 0, 0, box),
                    VISION_CONFIDENCE, item.checkLine() ? new DetectorSource("check-line", "1")
                    : new DetectorSource("vision", result.model().version()),
                    FindingOrigin.of(DetectionPath.VISION, item.checkLine() ? List.of() : List.of(result.model())),
                    modelBox(box, item.checkLine())));
        }
        if (!result.read()) {
            found.add(unread(result.page(), result.code() == null ? result.reason() : result.code(), List.of()));
        } else if (result.incomplete()) {
            found.add(unread(result.page(), "ITEM_NOT_PLACED", List.of(result.model())));
        }
        return found;
    }

    /**
     * Task 0100 (R4): a page the text reader could not read (not blank). What the vision model said of it stays, and a
     * person still checks the whole page: its reading can't be trusted, so it is never delivered on the model's word.
     */
    public static Candidate untrusted(int page, List<AiModel> models) {
        return unread(page, "TEXT_NOT_READABLE", models);
    }

    /**
     * Task 0103 (owner's run 2, long-60 pages 23 and 32): a person's values come together (SSN and date of birth). On a
     * page the vision model read, when one of them was found (by the model or the text) and another kind of the group
     * that the entity redacts was not, a person checks the page: the model may have missed it. Null when the group is
     * whole or absent.
     */
    static Candidate groupIncomplete(int page, List<Candidate> text, List<Candidate> vision, Set<EntityType> enabled,
                                     AiModel model) {
        Set<EntityType> wanted = java.util.EnumSet.noneOf(EntityType.class);
        if (enabled.contains(EntityType.SSN) || enabled.contains(EntityType.ITIN)) wanted.add(EntityType.SSN);
        if (enabled.contains(EntityType.DATE_OF_BIRTH)) wanted.add(EntityType.DATE_OF_BIRTH);
        if (wanted.size() < 2) return null;
        Set<EntityType> found = java.util.EnumSet.noneOf(EntityType.class);
        for (List<Candidate> all : List.of(text, vision)) {
            for (Candidate c : all) {
                if (c.location().pageNumber() != page) continue;
                if (c.type() == EntityType.SSN || c.type() == EntityType.ITIN) found.add(EntityType.SSN);
                if (c.type() == EntityType.DATE_OF_BIRTH) found.add(EntityType.DATE_OF_BIRTH);
            }
        }
        if (found.isEmpty() || found.containsAll(wanted)) return null;
        return unread(page, "GROUP_INCOMPLETE", model == null ? List.of() : List.of(model));
    }

    /** A page for a person: it has no place, so it can never be redacted or delivered without a decision. */
    private static Candidate unread(int page, String why, List<AiModel> models) {
        return new Candidate(EntityType.UNREAD_PAGE, FindingLocation.unlocated(page), 0.0,
                new DetectorSource("vision-page-check", why), FindingOrigin.of(DetectionPath.PAGE_CHECK, models),
                FindingPlacement.forPerson(Reader.PAGE_CHECK, Method.CHECK, How.PAGE, why == null ? "PAGE_CHECK" : why));
    }

    /** The entity's kind for what the model named; null when the entity does not redact it. */
    public static EntityType type(VisionPageResult.Kind kind, Set<EntityType> enabled) {
        return switch (kind) {
            // The model does not tell an ITIN from an SSN.
            case SSN -> first(enabled, EntityType.SSN, EntityType.ITIN);
            case DATE_OF_BIRTH -> first(enabled, EntityType.DATE_OF_BIRTH);
            case CREDIT_CARD_NUMBER -> first(enabled, EntityType.CREDIT_CARD_NUMBER);
            case CREDIT_SCORE -> first(enabled, EntityType.CREDIT_SCORE);
            case BANK_ACCOUNT -> first(enabled, EntityType.BANK_ACCOUNT_NUMBER);
            case ROUTING_NUMBER -> first(enabled, EntityType.BANK_ROUTING_NUMBER);
            // One kind for both: the model does not tell them apart.
            case GOVERNMENT_ID -> first(enabled, EntityType.DRIVERS_LICENSE_NUMBER, EntityType.PASSPORT_NUMBER);
            case SIGNATURE -> first(enabled, EntityType.SIGNATURE);
            case FACE_PHOTO -> first(enabled, EntityType.FACE_PHOTO);
        };
    }

    private static EntityType first(Set<EntityType> enabled, EntityType... types) {
        for (EntityType type : types) if (enabled.contains(type)) return type;
        return null;
    }

    /** A box smaller than a character or larger than a quarter of the page. */
    static final double MIN_SIDE = 0.002;
    static final double MAX_AREA = 0.25;
    static final String IMPLAUSIBLE_BOX = "MODEL_BOX_IMPLAUSIBLE";

    /**
     * Task 0105, step 2a: the model's box is its place when it lies on the page (it is kept on it) and has a size a value
     * can have. Otherwise it is still covered, and a person checks it, with the reason.
     */
    static FindingPlacement modelBox(BoundingBox box, boolean checkLine) {
        Method method = checkLine ? Method.PATTERN : Method.AI_MODEL;
        boolean plausible = box.width() >= MIN_SIDE && box.height() >= MIN_SIDE && box.width() * box.height() <= MAX_AREA;
        return plausible ? FindingPlacement.automatic(Reader.VISION, method, How.MODEL_BOX)
                : FindingPlacement.forPerson(Reader.VISION, method, How.MODEL_BOX, IMPLAUSIBLE_BOX);
    }

    private static BoundingBox widened(BoundingBox b) {
        return box(b.left() - MARGIN, b.top() - MARGIN, b.left() + b.width() + MARGIN, b.top() + b.height() + MARGIN);
    }

    /** The box between two corners, kept on the page. */
    static BoundingBox box(double left, double top, double right, double bottom) {
        double l = Math.max(0, left);
        double t = Math.max(0, top);
        double width = Math.min(1, right) - l;
        double height = Math.min(1, bottom) - t;
        // Rounding must not push the box over the page edge.
        while (l + width > 1.0) width = Math.nextDown(width);
        while (t + height > 1.0) height = Math.nextDown(height);
        return BoundingBox.of(l, t, width, height);
    }

    /** {@code text} first and in its order; a finding at the place of an earlier one is merged into it. */
    static List<Candidate> merge(List<Candidate> text, List<Candidate> others) {
        List<Candidate> merged = new ArrayList<>(text);
        for (Candidate other : others) {
            int best = -1;
            double bestScore = 0;
            for (int i = 0; i < merged.size(); i++) {
                double score = samePlace(merged.get(i), other);
                if (score > bestScore) {
                    bestScore = score;
                    best = i;
                }
            }
            if (best < 0) merged.add(other);
            else merged.set(best, union(merged.get(best), other));
        }
        return merged;
    }

    /** 0 when the two are not at one place. */
    private static double samePlace(Candidate a, Candidate b) {
        // Task 0072: a person may dismiss a name; a value of another kind at its place stays a finding of its own.
        if (a.type() != b.type() && (a.type() == EntityType.PERSON_NAME || b.type() == EntityType.PERSON_NAME)) return 0;
        BoundingBox x = a.location().boundingBox();
        BoundingBox y = b.location().boundingBox();
        if (a.location().pageNumber() != b.location().pageNumber() || x == null || y == null) return 0;
        double areaX = x.width() * x.height();
        double areaY = y.width() * y.height();
        double smaller = Math.min(areaX, areaY);
        double larger = Math.max(areaX, areaY);
        double shared = shared(x, y);
        double iou = shared / (areaX + areaY - shared);
        if (a.type() == b.type() || sameFamily(a.type(), b.type())) {
            return shared / smaller >= SAME_KIND_OVERLAP && larger <= MAX_AREA_RATIO * smaller ? 1 + iou : 0;
        }
        return iou >= OTHER_KIND_IOU ? iou : 0;
    }

    private static boolean sameFamily(EntityType a, EntityType b) {
        return family(a) == family(b);
    }

    private static EntityType family(EntityType type) {
        return switch (type) {
            case ITIN -> EntityType.SSN;
            case PASSPORT_NUMBER -> EntityType.DRIVERS_LICENSE_NUMBER;
            default -> type;
        };
    }

    private static double shared(BoundingBox a, BoundingBox b) {
        double w = Math.min(a.left() + a.width(), b.left() + b.width()) - Math.max(a.left(), b.left());
        double h = Math.min(a.top() + a.height(), b.top() + b.height()) - Math.max(a.top(), b.top());
        return w <= 0 || h <= 0 ? 0 : w * h;
    }

    /** One finding that covers both places; the first one's kind, text place and detector are kept. */
    private static Candidate union(Candidate a, Candidate b) {
        BoundingBox x = a.location().boundingBox();
        BoundingBox y = b.location().boundingBox();
        BoundingBox both = box(Math.min(x.left(), y.left()), Math.min(x.top(), y.top()),
                Math.max(x.left() + x.width(), y.left() + y.width()),
                Math.max(x.top() + x.height(), y.top() + y.height()));
        double confidence = Math.max(a.confidence(), b.confidence());
        boolean agreement = a.type() == b.type()
                && !a.origin().paths().containsAll(b.origin().paths());
        if (agreement) confidence = Math.min(1.0, confidence + AGREEMENT_BOOST);
        return new Candidate(a.type(), FindingLocation.withBounds(a.location().pageNumber(),
                a.location().startOffset(), a.location().endOffset(), both), confidence, a.source(),
                a.origin().with(b.origin()), a.placement() != null ? a.placement() : b.placement());
    }

    // -------------------------------------------------------------------------------- the file of one vision job

    static void write(DataOutputStream out, VisionPageResult result) throws IOException {
        out.writeInt(result.page());
        out.writeBoolean(result.read());
        PageBatchFiles.writeText(out, result.code() == null ? "" : result.code());
        PageBatchFiles.writeText(out, result.reason() == null ? "" : result.reason());
        PageBatchFiles.writeText(out, result.model() == null ? "" : result.model().encode());
        out.writeBoolean(result.incomplete());
        out.writeInt(result.items().size());
        for (VisionPageResult.Item item : result.items()) {
            PageBatchFiles.writeText(out, item.kind().name() + (item.checkLine() ? CHECK_LINE : ""));
            out.writeDouble(item.box().left());
            out.writeDouble(item.box().top());
            out.writeDouble(item.box().width());
            out.writeDouble(item.box().height());
        }
    }

    static VisionPageResult read(DataInputStream in) throws IOException {
        int page = in.readInt();
        boolean read = in.readBoolean();
        String code = emptyToNull(PageBatchFiles.readText(in));
        String reason = emptyToNull(PageBatchFiles.readText(in));
        String model = emptyToNull(PageBatchFiles.readText(in));
        boolean incomplete = in.readBoolean();
        int count = in.readInt();
        List<VisionPageResult.Item> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String kind = PageBatchFiles.readText(in);
            boolean checkLine = kind.endsWith(CHECK_LINE);
            items.add(new VisionPageResult.Item(VisionPageResult.Kind.valueOf(checkLine
                    ? kind.substring(0, kind.length() - CHECK_LINE.length()) : kind), BoundingBox.of(in.readDouble(),
                    in.readDouble(), in.readDouble(), in.readDouble()), checkLine));
        }
        return new VisionPageResult(page, read, code, reason, model == null ? null : AiModel.decode(model), items,
                incomplete);
    }

    private static String emptyToNull(String text) {
        return text.isEmpty() ? null : text;
    }
}
