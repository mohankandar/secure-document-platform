package com.securedoc.benchmark;

import com.securedoc.benchmark.routing.PageSignals;
import com.securedoc.benchmark.score.Prediction;
import com.securedoc.benchmark.score.Scorer;
import com.securedoc.benchmark.truth.Truth;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntPredicate;

/** Writes the numbers of a run as markdown tables. Ids, counts, seconds and rates only. */
final class Summary {

    private static final Set<String> ALL_TYPES = new HashSet<>(Truth.ITEM_TYPES);

    private Summary() {
    }

    static String markdown(Results r, Map<String, Truth> truths) {
        StringBuilder md = new StringBuilder();
        int totalPages = r.documents.stream().mapToInt(d -> d.pages).sum();
        md.append("# Vision benchmark summary: ").append(r.set).append(" set\n\n");
        md.append("Run ").append(r.date).append(". ").append(r.documents.size()).append(" documents, ").append(totalPages)
                .append(" pages. GPU: ").append(r.gpu).append(". Ollama ").append(r.ollamaVersion)
                .append(", OLLAMA_NUM_PARALLEL ").append(r.ollamaNumParallel).append(".\n");
        md.append("Prompt ").append(r.promptVersion).append(" (sha256 ").append(r.promptSha256).append("). Routing threshold for the tables: OCR confidence below ")
                .append(f2(r.threshold)).append(". Vision ran on the pages routed at ").append(f2(r.visionThreshold))
                .append(" (the widest threshold of the sweep), so every threshold can be scored.\n");
        if (r.boxConventions != null && !r.boxConventions.isEmpty()) md.append("Box convention per model: ").append(r.boxConventions).append(".\n");
        md.append('\n');

        models(md, r);
        routing(md, r);
        textPath(md, r, truths);
        vision(md, r, truths);
        signatures(md, r, truths);
        combined(md, r, truths);
        sweep(md, r, truths);
        perDocument(md, r);
        concurrency(md, r);
        return md.toString();
    }

    // --- models ------------------------------------------------------------------------------------

    private static void models(StringBuilder md, Results r) {
        md.append("## Models\n\n| Role | Model | Digest | Size | Params | Quant | Licence (first line in the model) | Load s | First image s (untimed warm-up) | GPU baseline MiB | GPU peak MiB | Model VRAM MiB |\n|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        List<Results.ModelRun> runs = new ArrayList<>();
        if (r.textModel != null) runs.add(r.textModel);
        runs.addAll(r.visionModels);
        for (Results.ModelRun m : runs) {
            if (m.info == null) continue;
            long vram = m.vramBytes.getOrDefault(m.info.name(), 0L);
            md.append("| ").append(m == r.textModel ? "text" : "vision").append(" | ").append(m.info.name()).append(" | ")
                    .append(m.info.digest()).append(" | ").append(String.format(Locale.ROOT, "%.1f GB", m.info.sizeBytes() / 1e9)).append(" | ")
                    .append(m.info.parameterSize()).append(" | ").append(m.info.quantization()).append(" | ")
                    .append(m.info.licenceFirstLine().replace("|", "/")).append(" | ").append(f1(m.loadSeconds)).append(" | ")
                    .append(m == r.textModel ? "-" : f1(m.warmUpSeconds)).append(" | ").append(m.gpuBaselineMiB).append(" | ").append(m.gpuPeakMiB).append(" | ").append(vram / (1024 * 1024)).append(" |\n");
        }
        md.append('\n');
    }

    // --- routing -----------------------------------------------------------------------------------

    static String route(PageSignals s, double t) {
        return s.route(t).route();
    }

    private static void routing(StringBuilder md, Results r) {
        md.append("## Routing at ").append(f2(r.threshold)).append("\n\n");
        Map<String, Map<String, Integer>> byKind = new TreeMap<>();
        Map<String, Map<String, Integer>> confusion = new TreeMap<>();
        Map<String, Integer> reasons = new TreeMap<>();
        int pages = 0, ocrPages = 0, stopToday = 0;
        long ocrMs = 0, checkMs = 0;
        Set<String> docsStopToday = new HashSet<>();
        for (Results.DocResult d : r.documents) {
            for (int i = 0; i < d.pages; i++) {
                PageSignals s = d.routing.get(i);
                PageSignals.Decision decision = s.route(r.threshold);
                byKind.computeIfAbsent(d.kinds.get(i), k -> new TreeMap<>()).merge(decision.route(), 1, Integer::sum);
                String expected = d.expectedRoutes.get(i) == null ? "(not set)" : d.expectedRoutes.get(i);
                confusion.computeIfAbsent(expected, k -> new TreeMap<>()).merge(decision.route(), 1, Integer::sum);
                decision.reasons().forEach(x -> reasons.merge(x, 1, Integer::sum));
                pages++;
                ocrMs += s.ocrMillis();
                checkMs += s.checkMillis();
                if (s.ocrRun()) {
                    ocrPages++;
                    if (s.ocrConfidence() < 0.55) {
                        stopToday++;
                        docsStopToday.add(d.id);
                    }
                }
            }
        }
        md.append("Pages by kind and route:\n\n| Page kind | FAST | FAST_SIGNATURE | VISION |\n|---|---|---|---|\n");
        byKind.forEach((k, m) -> md.append("| ").append(k).append(" | ").append(m.getOrDefault("FAST", 0)).append(" | ")
                .append(m.getOrDefault("FAST_SIGNATURE", 0)).append(" | ").append(m.getOrDefault("VISION", 0)).append(" |\n"));
        md.append("\nExpected route (answer file) against the route taken:\n\n| Expected | FAST | FAST_SIGNATURE | VISION |\n|---|---|---|---|\n");
        confusion.forEach((k, m) -> md.append("| ").append(k).append(" | ").append(m.getOrDefault("FAST", 0)).append(" | ")
                .append(m.getOrDefault("FAST_SIGNATURE", 0)).append(" | ").append(m.getOrDefault("VISION", 0)).append(" |\n"));
        md.append("\nReasons for vision (a page can have several): ").append(reasons).append("\n\n");
        md.append("OCR ran on ").append(ocrPages).append(" of ").append(pages).append(" pages. ")
                .append(stopToday).append(" pages in ").append(docsStopToday.size())
                .append(" documents have OCR confidence below 0.55, where the product stops the case today (LV-PRC-003).\n");
        md.append("Routing cost per page: OCR ").append(f2(ocrMs / 1000.0 / Math.max(1, ocrPages))).append(" s per OCR page (the text path does the same OCR, so in the product it is shared); other checks ")
                .append(f3(checkMs / 1000.0 / Math.max(1, pages))).append(" s per page.\n\n");
        md.append("OCR confidence of the OCR pages, by kind (min / mean / max):\n\n| Page kind | Pages | Min | Mean | Max |\n|---|---|---|---|---|\n");
        Map<String, List<Double>> conf = new TreeMap<>();
        for (Results.DocResult d : r.documents) {
            for (int i = 0; i < d.pages; i++) {
                PageSignals s = d.routing.get(i);
                if (s.ocrRun()) conf.computeIfAbsent(d.kinds.get(i), k -> new ArrayList<>()).add(s.ocrConfidence());
            }
        }
        conf.forEach((k, v) -> md.append("| ").append(k).append(" | ").append(v.size()).append(" | ")
                .append(f2(v.stream().mapToDouble(x -> x).min().orElse(0))).append(" | ")
                .append(f2(v.stream().mapToDouble(x -> x).average().orElse(0))).append(" | ")
                .append(f2(v.stream().mapToDouble(x -> x).max().orElse(0))).append(" |\n"));
        md.append('\n');
    }

    // --- text path ---------------------------------------------------------------------------------

    private static void textPath(StringBuilder md, Results r, Map<String, Truth> truths) {
        List<Results.DocResult> docs = r.documents.stream().filter(d -> d.text != null).toList();
        if (docs.isEmpty()) return;
        double seconds = docs.stream().mapToDouble(d -> d.text.totalSeconds).sum();
        double extraction = docs.stream().mapToDouble(d -> d.text.extractionSeconds).sum();
        double model = docs.stream().mapToDouble(d -> d.text.modelSeconds).sum();
        int pages = docs.stream().mapToInt(d -> d.pages).sum();
        long failures = docs.stream().filter(d -> d.text.failure != null).count();
        md.append("## Text path (product code, ").append(r.textModel.info.name()).append(")\n\n");
        md.append("Seconds per page: ").append(f2(seconds / pages)).append(" (extraction and OCR ").append(f2(extraction / pages))
                .append(", text model ").append(f2(model / pages)).append(", rest ").append(f2((seconds - extraction - model) / pages))
                .append("). Documents that failed: ").append(failures).append(".\n\n");
        Scorer.Score all = new Scorer.Score();
        for (Results.DocResult d : docs) {
            all.add(Scorer.score(truths.get(d.id), d.text.findings, p -> true, Truth.TEXT_PATH_TYPES));
        }
        md.append("All pages, the text path's four types:\n\n");
        table(md, all);

        // Task 0043: the answer-file types the new pattern kinds cover, and the tax-ID kinds the samples never hold.
        String kinds = r.textKinds == null ? "v1" : r.textKinds;
        Scorer.Score r1 = new Scorer.Score();
        int taxIdFindings = 0;
        for (Results.DocResult d : docs) {
            r1.add(Scorer.score(truths.get(d.id), d.text.findings, p -> true, Truth.R1_PATTERN_TYPES));
            taxIdFindings += (int) d.text.findings.stream()
                    .filter(p -> p.type().equals("ITIN") || p.type().equals("EIN")).count();
        }
        md.append("Text kinds: ").append(kinds).append(" (").append("all".equals(kinds)
                ? "the task 0043 pattern kinds switched on" : "product default: the four V1 kinds only").append(").\n\n");
        md.append("All pages, the types the task 0043 kinds cover (routing, account, driver's license or passport):\n\n");
        table(md, r1);
        md.append("ITIN and EIN findings (the samples hold none, so each one is a false flag): ").append(taxIdFindings)
                .append(".\n\n");
    }

    // --- vision ------------------------------------------------------------------------------------

    static IntPredicate routed(Results.DocResult d, double t) {
        return p -> route(d.routing.get(p - 1), t).equals("VISION");
    }

    private static void vision(StringBuilder md, Results r, Map<String, Truth> truths) {
        for (Results.ModelRun m : r.visionModels) {
            String name = m.info.name();
            List<Double> secs = new ArrayList<>();
            int parseFailed = 0, errors = 0;
            Scorer.Score score = new Scorer.Score();
            Scorer.Score textSame = new Scorer.Score();
            for (Results.DocResult d : r.documents) {
                List<Results.VisionPage> pages = d.vision.getOrDefault(name, List.of());
                IntPredicate routed = routed(d, r.threshold);
                List<Prediction> preds = new ArrayList<>();
                for (Results.VisionPage p : pages) {
                    if (!routed.test(p.page())) continue;
                    if (p.error() != null) errors++;
                    else secs.add(p.seconds());
                    if (p.parseFailed()) parseFailed++;
                    preds.addAll(p.items());
                }
                score.add(Scorer.score(truths.get(d.id), preds, routed, ALL_TYPES));
                if (d.text != null) textSame.add(Scorer.score(truths.get(d.id), d.text.findings, routed, ALL_TYPES));
            }
            secs.sort(Double::compare);
            md.append("## Vision: ").append(name).append(" (pages routed at ").append(f2(r.threshold)).append(")\n\n");
            md.append("Pages: ").append(secs.size() + errors).append(". Seconds per page: mean ").append(f2(mean(secs)))
                    .append(", median ").append(f2(pct(secs, 0.5))).append(", 90th percentile ").append(f2(pct(secs, 0.9)))
                    .append(", max ").append(f2(secs.isEmpty() ? 0 : secs.get(secs.size() - 1))).append(". Unreadable answers: ").append(parseFailed)
                    .append(". Request errors: ").append(errors).append(".\n\n");
            md.append("Vision on the routed pages, all item types:\n\n");
            table(md, score);
            md.append("For comparison, the text path on the same pages, all item types:\n\n");
            table(md, textSame);
        }
    }

    private static void combined(StringBuilder md, Results r, Map<String, Truth> truths) {
        if (r.documents.stream().anyMatch(d -> d.text == null)) return;
        for (Results.ModelRun m : r.visionModels) {
            Scorer.Score score = combinedScore(r, truths, m.info.name(), r.threshold, ALL_TYPES);
            md.append("## Text path plus ").append(m.info.name())
                    .append(" on routed pages, plus the signature detector on the other pages (all pages, all item types)\n\n");
            table(md, score);
            md.append("Missed (document, page, kind, type): ").append(score.missed.isEmpty() ? "none" : String.join("; ", score.missed)).append("\n\n");
        }
        Scorer.Score textOnly = new Scorer.Score();
        for (Results.DocResult d : r.documents) textOnly.add(prefixed(d.id, Scorer.score(truths.get(d.id), d.text.findings, p -> true, ALL_TYPES)));
        md.append("## Text path alone (all pages, all item types)\n\n");
        table(md, textOnly);
        List<String> textTypeMisses = textOnly.missed.stream()
                .filter(x -> Truth.TEXT_PATH_TYPES.stream().anyMatch(x::endsWith)).toList();
        md.append("Missed, the text path's four types only (document, page, kind, type): ").append(String.join("; ", textTypeMisses)).append("\n\n");
    }

    static Scorer.Score combinedScore(Results r, Map<String, Truth> truths, String model, double t, Set<String> types) {
        Scorer.Score score = new Scorer.Score();
        for (Results.DocResult d : r.documents) {
            IntPredicate routed = routed(d, t);
            List<Prediction> preds = new ArrayList<>(d.text == null ? List.of() : d.text.findings);
            for (Results.VisionPage p : d.vision.getOrDefault(model, List.of())) {
                if (routed.test(p.page())) preds.addAll(p.items());
            }
            preds.addAll(signatureDetector(d, t));
            score.add(prefixed(d.id, Scorer.score(truths.get(d.id), preds, x -> true, types)));
        }
        return score;
    }

    /** The signature detector's regions on pages that stay on the fast path, as SIGNATURE findings. */
    static List<Prediction> signatureDetector(Results.DocResult d, double t) {
        List<Prediction> out = new ArrayList<>();
        for (PageSignals s : d.routing) {
            if (route(s, t).equals("VISION")) continue;
            for (double[] box : s.signatureRegions()) out.add(new Prediction(s.page(), "SIGNATURE", box, "router:signature"));
        }
        return out;
    }

    static Scorer.Score prefixed(String doc, Scorer.Score s) {
        s.missed.replaceAll(x -> doc + " " + x);
        return s;
    }

    private static void signatures(StringBuilder md, Results r, Map<String, Truth> truths) {
        Scorer.Score score = new Scorer.Score();
        for (Results.DocResult d : r.documents) {
            IntPredicate fast = p -> !route(d.routing.get(p - 1), r.threshold).equals("VISION");
            score.add(prefixed(d.id, Scorer.score(truths.get(d.id), signatureDetector(d, r.threshold), fast, Set.of("SIGNATURE"))));
        }
        md.append("## Signature detector on the pages that stay on the fast path\n\n");
        table(md, score);
        md.append("Missed (document, page, kind, type): ").append(score.missed.isEmpty() ? "none" : String.join("; ", score.missed)).append("\n\n");
    }

    // --- threshold sweep and time per document -----------------------------------------------------

    private static void sweep(StringBuilder md, Results r, Map<String, Truth> truths) {
        int pages = r.documents.stream().mapToInt(d -> d.pages).sum();
        List<Results.DocResult> withText = r.documents.stream().filter(d -> d.text != null).toList();
        double textPerPage = withText.isEmpty() ? Double.NaN
                : withText.stream().mapToDouble(d -> d.text.totalSeconds).sum() / withText.stream().mapToInt(d -> d.pages).sum();
        double checksPerPage = r.documents.stream().flatMap(d -> d.routing.stream()).mapToLong(PageSignals::checkMillis).sum() / 1000.0 / Math.max(1, pages);
        Results.DocResult pkg = r.documents.stream().filter(d -> d.pages >= 50).findFirst().orElse(null);
        md.append("## Threshold sweep and estimated seconds per 60-page document\n\n");
        md.append("Estimate = 60 x text-path seconds per page (").append(f2(textPerPage)).append(") + 60 x routing checks (")
                .append(f3(checksPerPage)).append(") + pages routed per 60 x vision seconds per page (sequential, one request at a time).\n");
        md.append("\"Routed per 60\" is the share of all pages in this set scaled to 60; this set is weighted towards hard pages on purpose.");
        if (pkg != null) md.append(" The 60-page package (").append(pkg.id).append(") is measured directly.");
        md.append("\n\n| Threshold | Pages to vision (set) | Routed per 60 (set share) | Package: pages to vision |");
        for (Results.ModelRun m : r.visionModels) md.append(' ').append(m.info.name()).append(": misses / false flags (combined) | ")
                .append(m.info.name()).append(": est. s per 60 pages (set share) | ").append(m.info.name()).append(": package measured s |");
        md.append("\n|---|---|---|---|");
        for (int i = 0; i < r.visionModels.size(); i++) md.append("---|---|---|");
        md.append('\n');
        for (double t : r.sweep) {
            int routedAll = 0;
            for (Results.DocResult d : r.documents) for (PageSignals s : d.routing) if (route(s, t).equals("VISION")) routedAll++;
            double per60 = 60.0 * routedAll / Math.max(1, pages);
            int pkgRouted = pkg == null ? 0 : (int) pkg.routing.stream().filter(s -> route(s, t).equals("VISION")).count();
            md.append("| ").append(f2(t)).append(" | ").append(routedAll).append(" | ").append(f1(per60)).append(" | ")
                    .append(pkg == null ? "-" : Integer.toString(pkgRouted)).append(" |");
            for (Results.ModelRun m : r.visionModels) {
                String name = m.info.name();
                Scorer.Score s = combinedScore(r, truths, name, t, ALL_TYPES);
                double vps = visionSecondsPerPage(r, name);
                double est = 60 * textPerPage + 60 * checksPerPage + per60 * vps;
                String measured = "-";
                if (pkg != null && pkg.text != null) {
                    double pv = 0;
                    for (Results.VisionPage p : pkg.vision.getOrDefault(name, List.of())) {
                        if (route(pkg.routing.get(p.page() - 1), t).equals("VISION")) pv += p.seconds();
                    }
                    double pc = pkg.routing.stream().mapToLong(PageSignals::checkMillis).sum() / 1000.0;
                    measured = f1(pkg.text.totalSeconds + pc + pv);
                }
                md.append(' ').append(s.total.misses()).append(" / ").append(s.total.falseFlags).append(" | ").append(f1(est))
                        .append(" | ").append(measured).append(" |");
            }
            md.append('\n');
        }
        md.append('\n');
    }

    static double visionSecondsPerPage(Results r, String model) {
        List<Double> secs = new ArrayList<>();
        for (Results.DocResult d : r.documents) {
            for (Results.VisionPage p : d.vision.getOrDefault(model, List.of())) if (p.error() == null) secs.add(p.seconds());
        }
        return mean(secs);
    }

    private static void perDocument(StringBuilder md, Results r) {
        md.append("## Per document\n\n| Document | Pages | Kinds | To vision at ").append(f2(r.threshold)).append(" | Text path s |");
        for (Results.ModelRun m : r.visionModels) md.append(' ').append(m.info.name()).append(" s |");
        md.append("\n|---|---|---|---|---|");
        for (int i = 0; i < r.visionModels.size(); i++) md.append("---|");
        md.append('\n');
        for (Results.DocResult d : r.documents) {
            Map<String, Integer> kinds = new LinkedHashMap<>();
            d.kinds.forEach(k -> kinds.merge(k, 1, Integer::sum));
            IntPredicate routed = routed(d, r.threshold);
            long toVision = d.routing.stream().filter(s -> routed.test(s.page())).count();
            md.append("| ").append(d.id).append(" | ").append(d.pages).append(" | ").append(kinds).append(" | ").append(toVision)
                    .append(" | ").append(d.text == null ? "-" : f1(d.text.totalSeconds) + (d.text.failure == null ? "" : " (stopped)")).append(" |");
            for (Results.ModelRun m : r.visionModels) {
                double s = 0;
                for (Results.VisionPage p : d.vision.getOrDefault(m.info.name(), List.of())) if (routed.test(p.page())) s += p.seconds();
                md.append(' ').append(f1(s)).append(" |");
            }
            md.append('\n');
        }
        md.append('\n');
    }

    private static void concurrency(StringBuilder md, Results r) {
        md.append("## Requests at once (vision)\n\n| Model | At once | Mean latency s | Max latency s | Pages per minute |\n|---|---|---|---|---|\n");
        for (Results.ModelRun m : r.visionModels) {
            for (Results.ConcurrencyLevel c : m.concurrency) {
                md.append("| ").append(m.info.name()).append(" | ").append(c.requests()).append(" | ").append(f2(c.meanLatencySeconds()))
                        .append(" | ").append(f2(c.maxLatencySeconds())).append(" | ").append(f1(c.pagesPerMinute())).append(" |\n");
            }
        }
        md.append('\n');
    }

    // --- tables ------------------------------------------------------------------------------------

    private static void table(StringBuilder md, Scorer.Score s) {
        md.append("| Group | Expected | Found | Misses | Found without box | False flags | Duplicates |\n|---|---|---|---|---|---|---|\n");
        s.byType.forEach((k, t) -> row(md, "type " + k, t));
        s.byKind.forEach((k, t) -> row(md, "page kind " + k, t));
        row(md, "handwritten items", s.handwritten);
        row(md, "**total**", s.total);
        md.append('\n');
    }

    private static void row(StringBuilder md, String label, Scorer.Tally t) {
        md.append("| ").append(label).append(" | ").append(t.expected).append(" | ").append(t.found).append(" | ").append(t.misses())
                .append(" | ").append(t.foundWithoutBox).append(" | ").append(t.falseFlags).append(" | ").append(t.duplicates).append(" |\n");
    }

    static double mean(List<Double> v) {
        return v.stream().mapToDouble(x -> x).average().orElse(Double.NaN);
    }

    static double pct(List<Double> sorted, double q) {
        if (sorted.isEmpty()) return Double.NaN;
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.floor(q * (sorted.size() - 1) + 0.5)));
    }

    private static String f1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String f2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String f3(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}
