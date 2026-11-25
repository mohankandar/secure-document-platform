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
                    FindingOrigin.of(DetectionPath.SIGNATURE, List.of())));
        }
        return found;
    }

    /** The page's vision findings, of the kinds the entity redacts; a page that needs a person says so. */
    static List<Candidate> of(VisionPageResult result, TenantRedactionPolicy policy) {
        List<Candidate> found = new ArrayList<>();
        for (VisionPageResult.Item item : result.items()) {
            EntityType type = type(item.kind(), policy.enabledEntityTypes());
            if (type == null) continue;
            found.add(new Candidate(type, FindingLocation.withBounds(result.page(), 0, 0, widened(item.box())),
                    VISION_CONFIDENCE, new DetectorSource("vision", result.model().version()),
                    FindingOrigin.of(DetectionPath.VISION, List.of(result.model()))));
        }
        if (!result.read()) {
            found.add(unread(result.page(), result.code() == null ? result.reason() : result.code(), List.of()));
        } else if (result.incomplete()) {
            found.add(unread(result.page(), "ITEM_NOT_PLACED", List.of(result.model())));
        }
        return found;
    }

    /** A page for a person: it has no place, so it can never be redacted or delivered without a decision. */
    private static Candidate unread(int page, String why, List<AiModel> models) {
        return new Candidate(EntityType.UNREAD_PAGE, FindingLocation.unlocated(page), 0.0,
                new DetectorSource("vision-page-check", why), FindingOrigin.of(DetectionPath.PAGE_CHECK, models));
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
                a.origin().with(b.origin()));
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
            PageBatchFiles.writeText(out, item.kind().name());
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
            VisionPageResult.Kind kind = VisionPageResult.Kind.valueOf(PageBatchFiles.readText(in));
            items.add(new VisionPageResult.Item(kind, BoundingBox.of(in.readDouble(), in.readDouble(),
                    in.readDouble(), in.readDouble())));
        }
        return new VisionPageResult(page, read, code, reason, model == null ? null : AiModel.decode(model), items,
                incomplete);
    }

    private static String emptyToNull(String text) {
        return text.isEmpty() ? null : text;
    }
}
