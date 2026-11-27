package com.securedoc.platform.application.detection.service;

import com.securedoc.platform.application.pipeline.PageBatch;
import com.securedoc.platform.application.pipeline.PageBatchFiles;
import com.securedoc.platform.domain.finding.FindingOrigin;
import com.securedoc.platform.domain.vo.Confidence;
import com.securedoc.platform.domain.finding.AiModel;
import com.securedoc.platform.domain.finding.DetectorSource;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.finding.FindingLocation;
import com.securedoc.platform.domain.finding.FindingLocation.BoundingBox;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Task 0049: the findings of a document's page batches: their file form (places, kinds, confidence and detector; never
 * a value or text, as findings themselves) and their merging. Pages two batches share were searched twice; their
 * findings become one per place, as Find merges duplicates within one run.
 */
final class PageBatchFindings {

    /** A finding before the entity's confidence policy. */
    record Candidate(EntityType type, FindingLocation location, double confidence, DetectorSource source,
                     FindingOrigin origin, com.securedoc.platform.domain.finding.FindingPlacement placement) {
        /** Task 0105: placement null: decided when the finding is saved, from its origin. */
        Candidate(EntityType type, FindingLocation location, double confidence, DetectorSource source, FindingOrigin origin) {
            this(type, location, confidence, source, origin, null);
        }

        static Candidate of(Finding finding) {
            return new Candidate(finding.entityType(), finding.location(), finding.confidence().value(),
                    finding.detectorSource(), finding.origin(), finding.placement());
        }

        /** No offsets and no box: a page-level finding for a person. */
        boolean unlocated() {
            return location.startOffset() == location.endOffset() && !location.hasBoundingBox();
        }

        boolean samePlace(Candidate other) {
            return type == other.type && location.pageNumber() == other.location.pageNumber()
                    && location.startOffset() < other.location.endOffset()
                    && other.location.startOffset() < location.endOffset();
        }
    }

    /** One batch's result. {@code policyStamp}: the kinds it looked for. {@code models}: the AI models asked. */
    record Result(String policyStamp, int unlocatedSemantic, List<Candidate> candidates, List<AiModel> models) { }

    private PageBatchFindings() {
    }

    static void write(DataOutputStream out, Result result) throws IOException {
        PageBatchFiles.writeText(out, result.policyStamp());
        out.writeInt(result.unlocatedSemantic());
        out.writeInt(result.candidates().size());
        for (Candidate c : result.candidates()) {
            PageBatchFiles.writeText(out, c.type().name());
            out.writeInt(c.location().pageNumber());
            out.writeInt(c.location().startOffset());
            out.writeInt(c.location().endOffset());
            BoundingBox box = c.location().boundingBox();
            out.writeBoolean(box != null);
            if (box != null) {
                out.writeDouble(box.left());
                out.writeDouble(box.top());
                out.writeDouble(box.width());
                out.writeDouble(box.height());
            }
            out.writeDouble(c.confidence());
            PageBatchFiles.writeText(out, c.source().detectorName());
            PageBatchFiles.writeText(out, c.source().version());
        }
        // Task 0064: at the end, so a result written before it can still be read.
        for (Candidate c : result.candidates()) {
            PageBatchFiles.writeText(out, orEmpty(c.origin().encodePaths()));
            PageBatchFiles.writeText(out, orEmpty(c.origin().encodeModels()));
        }
        PageBatchFiles.writeText(out, orEmpty(FindingOrigin.encodeModels(result.models())));
        // Task 0105: at the end, so a result written before it can still be read.
        for (Candidate c : result.candidates()) {
            PageBatchFiles.writeText(out, c.placement() == null ? "" : c.placement().encode());
        }
    }

    private static String orEmpty(String text) {
        return text == null ? "" : text;
    }

    static Result read(DataInputStream in) throws IOException {
        String stamp = PageBatchFiles.readText(in);
        int unlocated = in.readInt();
        int count = in.readInt();
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            EntityType type = EntityType.valueOf(PageBatchFiles.readText(in));
            int page = in.readInt();
            int start = in.readInt();
            int end = in.readInt();
            BoundingBox box = in.readBoolean()
                    ? BoundingBox.of(in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble()) : null;
            double confidence = Confidence.of(in.readDouble()).value();
            candidates.add(new Candidate(type, new FindingLocation(page, start, end, box), confidence,
                    new DetectorSource(PageBatchFiles.readText(in), PageBatchFiles.readText(in)),
                    FindingOrigin.NOT_RECORDED));
        }
        List<AiModel> models = List.of();
        if (in.available() > 0) {
            for (int i = 0; i < count; i++) {
                Candidate c = candidates.get(i);
                candidates.set(i, new Candidate(c.type(), c.location(), c.confidence(), c.source(),
                        FindingOrigin.decode(PageBatchFiles.readText(in), PageBatchFiles.readText(in))));
            }
            models = FindingOrigin.decodeModels(PageBatchFiles.readText(in));
        }
        if (in.available() > 0) {
            for (int i = 0; i < count; i++) {
                Candidate c = candidates.get(i);
                candidates.set(i, new Candidate(c.type(), c.location(), c.confidence(), c.source(), c.origin(),
                        com.securedoc.platform.domain.finding.FindingPlacement.decode(PageBatchFiles.readText(in))));
            }
        }
        return new Result(stamp, unlocated, candidates, models);
    }

    /**
     * The document's findings. Two findings of one kind whose places overlap are one: its place is the union of both
     * (so the box never covers less than either) and the higher confidence is kept, with its detector. The second
     * batch is not independent evidence, so agreement gives no confidence boost. Findings without a place cannot be
     * compared by place: of a page that several batches searched, the batch with the most of a kind and detector
     * counts (one batch's findings are never all dropped, and one finding is not counted twice).
     */
    static List<Candidate> merge(List<PageBatch> batches, List<Result> results) {
        if (batches.size() != results.size()) throw new IllegalArgumentException("One result per batch");
        List<Candidate> located = new ArrayList<>();
        Map<Unlocated, List<Candidate>> unlocated = new LinkedHashMap<>();
        for (int b = 0; b < results.size(); b++) {
            PageBatch batch = batches.get(b);
            Map<Unlocated, List<Candidate>> ofBatch = new LinkedHashMap<>();
            for (Candidate candidate : results.get(b).candidates()) {
                if (!batch.covers(candidate.location().pageNumber())) {
                    throw new IllegalStateException("A page batch result has a finding on a page it did not search");
                }
                if (candidate.unlocated()) {
                    ofBatch.computeIfAbsent(new Unlocated(candidate.type(), candidate.location().pageNumber(),
                            candidate.source().detectorName()), key -> new ArrayList<>()).add(candidate);
                } else {
                    addLocated(located, candidate);
                }
            }
            ofBatch.forEach((key, found) -> unlocated.merge(key, found, (a, c) -> c.size() > a.size() ? c : a));
        }
        List<Candidate> merged = new ArrayList<>(located);
        merged.sort(Comparator.comparingInt((Candidate c) -> c.location().pageNumber())
                .thenComparingInt(c -> c.location().startOffset())
                .thenComparingInt(c -> c.location().endOffset()));
        unlocated.values().forEach(merged::addAll);
        return merged;
    }

    private record Unlocated(EntityType type, int page, String detector) { }

    /** A union can reach a further finding, so it is compared again until nothing overlaps. */
    private static void addLocated(List<Candidate> located, Candidate added) {
        Candidate candidate = added;
        for (int i = 0; i < located.size(); ) {
            if (located.get(i).samePlace(candidate)) {
                candidate = union(located.remove(i), candidate);
                i = 0;
            } else {
                i++;
            }
        }
        located.add(candidate);
    }

    private static Candidate union(Candidate a, Candidate b) {
        Candidate surer = b.confidence() > a.confidence() ? b : a;
        FindingLocation place = new FindingLocation(a.location().pageNumber(),
                Math.min(a.location().startOffset(), b.location().startOffset()),
                Math.max(a.location().endOffset(), b.location().endOffset()),
                union(a.location().boundingBox(), b.location().boundingBox()));
        return new Candidate(a.type(), place, surer.confidence(), surer.source(), a.origin().with(b.origin()),
                surer.placement() != null ? surer.placement() : a.placement() != null ? a.placement() : b.placement());
    }

    private static BoundingBox union(BoundingBox a, BoundingBox b) {
        // A place without a box cannot be redacted on its own: without both boxes the union has none (a person looks).
        if (a == null || b == null) return null;
        if (a.equals(b)) return a;
        double left = Math.min(a.left(), b.left());
        double top = Math.min(a.top(), b.top());
        double right = Math.min(1.0, Math.max(a.left() + a.width(), b.left() + b.width()));
        double bottom = Math.min(1.0, Math.max(a.top() + a.height(), b.top() + b.height()));
        double width = right - left;
        double height = bottom - top;
        // Rounding must not push the box over the page edge.
        if (left + width > 1.0) width = Math.nextDown(width);
        if (top + height > 1.0) height = Math.nextDown(height);
        return BoundingBox.of(left, top, width, height);
    }
}
