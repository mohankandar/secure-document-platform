package com.securedoc.benchmark.score;

import com.securedoc.benchmark.truth.Truth;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntPredicate;

/**
 * Compares what a path found with the answer file.
 *
 * <p>A found item matches an answer item when it is on the same page, has the same type and its box
 * overlaps: the overlap covers at least 30% of the smaller box, or the centre of the answer box lies inside
 * the found box (widened by 1% of the page); and the found box is at most 10 times the item's area (or 2%
 * of the page, whichever is larger), so a box over half the page does not count. A found item with no box matches an answer item of the same
 * type on the same page, and is counted as "found without a box" (the product sends those to review).
 * A found item that matches nothing is a false flag, unless it repeats an answer item already matched
 * (a duplicate).
 */
public final class Scorer {

    public static final double MIN_OVERLAP = 0.3;
    public static final double CENTRE_MARGIN = 0.01;
    /** A found box may be at most 10 times the item's area, or 2% of the page, whichever is larger. */
    public static final double MAX_LOOSE_AREA = 0.02;

    /** Counters for one group (a type, a page kind, ...). */
    public static final class Tally {
        public int expected;
        public int found;
        public int foundWithoutBox;
        public int falseFlags;
        public int duplicates;

        public int misses() {
            return expected - found;
        }

        void add(Tally o) {
            expected += o.expected;
            found += o.found;
            foundWithoutBox += o.foundWithoutBox;
            falseFlags += o.falseFlags;
            duplicates += o.duplicates;
        }
    }

    /** Tallies by type, by page kind, for handwritten items, and in total. */
    public static final class Score {
        public final Map<String, Tally> byType = new TreeMap<>();
        public final Map<String, Tally> byKind = new TreeMap<>();
        public final Tally handwritten = new Tally();
        public final Tally total = new Tally();
        /** Missed answer items as "page kind type" (never a value). */
        public final List<String> missed = new ArrayList<>();

        public void add(Score o) {
            o.byType.forEach((k, v) -> byType.computeIfAbsent(k, x -> new Tally()).add(v));
            o.byKind.forEach((k, v) -> byKind.computeIfAbsent(k, x -> new Tally()).add(v));
            handwritten.add(o.handwritten);
            total.add(o.total);
            missed.addAll(o.missed);
        }

        Tally type(String t) {
            return byType.computeIfAbsent(t, x -> new Tally());
        }

        Tally kind(String k) {
            return byKind.computeIfAbsent(k, x -> new Tally());
        }
    }

    private Scorer() {
    }

    /**
     * @param pages which pages count (for example only the pages routed to vision)
     * @param types which item types count (for example the text path's four types)
     */
    public static Score score(Truth truth, List<Prediction> predictions, IntPredicate pages, Set<String> types) {
        Score score = new Score();
        for (Truth.PageTruth page : truth.pages()) {
            if (!pages.test(page.page())) continue;
            List<Prediction> preds = new ArrayList<>();
            for (Prediction p : predictions) {
                if (p.page() == page.page() && types.contains(p.type())) preds.add(p);
            }
            boolean[] used = new boolean[preds.size()];
            List<Truth.Item> items = page.items().stream().filter(i -> types.contains(i.type())).toList();
            for (Truth.Item item : items) {
                score.type(item.type()).expected++;
                score.kind(page.kind()).expected++;
                score.total.expected++;
                if (item.isHandwritten()) score.handwritten.expected++;
                int best = -1;
                double bestOverlap = -1;
                for (int i = 0; i < preds.size(); i++) {
                    Prediction p = preds.get(i);
                    if (used[i] || p.box() == null || !p.type().equals(item.type())) continue;
                    if (boxesMatch(item.box(), p.box())) {
                        double o = overlap(item.box(), p.box());
                        if (o > bestOverlap) {
                            bestOverlap = o;
                            best = i;
                        }
                    }
                }
                boolean withoutBox = false;
                if (best < 0) {
                    for (int i = 0; i < preds.size(); i++) {
                        Prediction p = preds.get(i);
                        if (!used[i] && p.box() == null && p.type().equals(item.type())) {
                            best = i;
                            withoutBox = true;
                            break;
                        }
                    }
                }
                if (best < 0) score.missed.add("p" + page.page() + " " + page.kind() + " " + item.type());
                if (best >= 0) {
                    used[best] = true;
                    score.type(item.type()).found++;
                    score.kind(page.kind()).found++;
                    score.total.found++;
                    if (item.isHandwritten()) score.handwritten.found++;
                    if (withoutBox) {
                        score.type(item.type()).foundWithoutBox++;
                        score.kind(page.kind()).foundWithoutBox++;
                        score.total.foundWithoutBox++;
                        if (item.isHandwritten()) score.handwritten.foundWithoutBox++;
                    }
                }
            }
            for (int i = 0; i < preds.size(); i++) {
                if (used[i]) continue;
                Prediction p = preds.get(i);
                boolean repeats = items.stream().anyMatch(item -> item.type().equals(p.type())
                        && (p.box() == null || boxesMatch(item.box(), p.box())));
                if (repeats) {
                    score.type(p.type()).duplicates++;
                    score.kind(page.kind()).duplicates++;
                    score.total.duplicates++;
                } else {
                    score.type(p.type()).falseFlags++;
                    score.kind(page.kind()).falseFlags++;
                    score.total.falseFlags++;
                }
            }
        }
        return score;
    }

    static boolean boxesMatch(double[] truth, double[] found) {
        // A box far larger than the item would black out much more than the item: not a match.
        double truthArea = truth[2] * truth[3], foundArea = found[2] * found[3];
        if (foundArea > Math.max(10 * truthArea, MAX_LOOSE_AREA)) return false;
        if (overlap(truth, found) >= MIN_OVERLAP) return true;
        double cx = truth[0] + truth[2] / 2, cy = truth[1] + truth[3] / 2;
        return cx >= found[0] - CENTRE_MARGIN && cx <= found[0] + found[2] + CENTRE_MARGIN
                && cy >= found[1] - CENTRE_MARGIN && cy <= found[1] + found[3] + CENTRE_MARGIN;
    }

    /** Intersection area over the smaller box's area. */
    static double overlap(double[] a, double[] b) {
        double l = Math.max(a[0], b[0]), t = Math.max(a[1], b[1]);
        double r = Math.min(a[0] + a[2], b[0] + b[2]), bo = Math.min(a[1] + a[3], b[1] + b[3]);
        if (r <= l || bo <= t) return 0;
        double inter = (r - l) * (bo - t);
        double smaller = Math.min(a[2] * a[3], b[2] * b[3]);
        return smaller <= 0 ? 0 : inter / smaller;
    }
}
