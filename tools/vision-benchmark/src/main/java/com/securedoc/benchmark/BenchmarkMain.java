package com.securedoc.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.securedoc.benchmark.routing.PageRouter;
import com.securedoc.benchmark.routing.PageSignals;
import com.securedoc.benchmark.samples.SampleGenerator;
import com.securedoc.benchmark.textpath.TextPathRunner;
import com.securedoc.benchmark.truth.Truth;
import com.securedoc.benchmark.vision.GpuSampler;
import com.securedoc.benchmark.vision.Ollama;
import com.securedoc.benchmark.vision.VisionClient;
import com.securedoc.platform.infrastructure.ocr.TesseractOcrAdapter;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Task 0033 benchmark. Commands:
 * <pre>
 *   generate  --root C:\dev\benchmark-samples                 build the part A samples in root\public
 *   run       --root ... --set public|customer [options]      run the benchmark on one set
 *   summarize --results path\results.json                     rewrite summary.md from saved results
 *   probe     --root ... --models a,b [--with-text m]         requests at once, and text + vision resident (task 0040)
 * </pre>
 * Options for run: {@code --text-model qwen3.5:9b}, {@code --models a,b,c}, {@code --threshold 0.75},
 * {@code --ollama http://localhost:11434}, {@code --skip-text}, {@code --skip-vision}, {@code --only id,id},
 * {@code --prompt v1|v2}, {@code --boxes model=PIXELS|PER_MILLE|UNIT,...}.
 *
 * <p>Console output and results hold ids, numbers, types and boxes only.
 */
public final class BenchmarkMain {

    static final List<Double> SWEEP = List.of(0.55, 0.65, 0.75, 0.85, 0.90);
    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("usage: generate|run|summarize [options]  (see README.md)");
            System.exit(2);
        }
        Map<String, String> opts = options(Arrays.copyOfRange(args, 1, args.length));
        switch (args[0]) {
            case "generate" -> generate(Path.of(required(opts, "root")));
            case "run" -> run(opts);
            case "probe" -> Probe.run(opts);
            case "summarize" -> {
                Path file = Path.of(required(opts, "results"));
                Results results = JSON.readValue(file.toFile(), Results.class);
                Map<String, Truth> truths = loadTruths(Path.of(required(opts, "root")), results.set);
                Files.writeString(file.resolveSibling("summary.md"), Summary.markdown(results, truths), StandardCharsets.UTF_8);
                System.out.println("summary written next to the results file");
            }
            default -> {
                System.out.println("unknown command " + args[0]);
                System.exit(2);
            }
        }
    }

    static void generate(Path root) throws IOException {
        SampleSet.refuseInsideRepository(root);
        Path folder = root.resolve("public");
        Files.createDirectories(root.resolve("customer"));
        Map<String, Truth> made = new SampleGenerator(33).generate(folder);
        int pages = made.values().stream().mapToInt(t -> t.pages().size()).sum();
        System.out.println("generated " + made.size() + " documents, " + pages + " pages, in the public folder");
    }

    static Map<String, Truth> loadTruths(Path root, String set) throws IOException {
        Map<String, Truth> truths = new LinkedHashMap<>();
        for (SampleSet.Sample s : SampleSet.load(root, set).samples()) truths.put(s.id(), s.truth());
        return truths;
    }

    static void run(Map<String, String> opts) throws Exception {
        Path root = Path.of(required(opts, "root"));
        String set = opts.getOrDefault("set", "public");
        SampleSet samples = SampleSet.load(root, set);
        List<String> only = opts.containsKey("only") ? List.of(opts.get("only").split(",")) : List.of();
        List<SampleSet.Sample> chosen = samples.samples().stream().filter(s -> only.isEmpty() || only.contains(s.id())).toList();
        Ollama ollama = new Ollama(opts.getOrDefault("ollama", "http://localhost:11434"));
        String textModel = opts.getOrDefault("text-model", "qwen3.5:9b");
        List<String> visionModels = List.of(opts.getOrDefault("models", "qwen2.5vl:7b,qwen3-vl:8b-instruct,minicpm-v:8b").split(","));

        Results results = new Results();
        results.date = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        results.set = set;
        results.gpu = GpuSampler.gpuName();
        results.ollamaNumParallel = System.getenv().getOrDefault("OLLAMA_NUM_PARALLEL", "not set (server default)");
        String promptVersion = opts.getOrDefault("prompt", VisionClient.DEFAULT_PROMPT);
        Map<String, VisionClient.BoxConvention> boxes = boxOverrides(opts.get("boxes"));
        VisionClient client = new VisionClient(ollama, promptVersion, boxes);
        results.promptVersion = VisionClient.promptName(promptVersion);
        results.promptSha256 = VisionClient.promptSha256(promptVersion);
        for (String m : visionModels) results.boxConventions.put(m, client.convention(m).name());
        // Task 0043: "v1" (default) runs the text path with the product's default kinds; "all" also switches on the
        // opt-in pattern kinds (routing, account, ITIN, EIN, driver's license, passport).
        results.textKinds = opts.getOrDefault("text-kinds", "v1");
        if (!List.of("v1", "all").contains(results.textKinds)) {
            throw new IllegalArgumentException("--text-kinds must be v1 or all");
        }
        results.threshold = Double.parseDouble(opts.getOrDefault("threshold", "0.75"));
        results.sweep = SWEEP;
        results.visionThreshold = Math.max(results.threshold, SWEEP.get(SWEEP.size() - 1));
        Path out = root.resolve("results").resolve(set + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        Files.createDirectories(out);

        // 1. Routing checks on every page.
        PageRouter router = new PageRouter(new TesseractOcrAdapter(TextPathRunner.productOcrProperties()));
        Map<String, Truth> truths = new LinkedHashMap<>();
        for (SampleSet.Sample sample : chosen) {
            truths.put(sample.id(), sample.truth());
            Results.DocResult doc = new Results.DocResult();
            doc.id = sample.id();
            try (PDDocument pdf = Loader.loadPDF(sample.pdf().toFile())) {
                doc.pages = pdf.getNumberOfPages();
                if (doc.pages != sample.truth().pages().size()) {
                    throw new IOException("Answer file of " + sample.id() + " lists " + sample.truth().pages().size()
                            + " pages; the PDF has " + doc.pages);
                }
                for (int i = 0; i < doc.pages; i++) {
                    Truth.PageTruth pt = sample.truth().page(i + 1);
                    if (pt == null) throw new IOException("Answer file of " + sample.id() + " has no page " + (i + 1));
                    doc.kinds.add(pt.kind());
                    doc.expectedRoutes.add(pt.expectedRoute());
                    doc.routing.add(router.analyze(pdf, i));
                }
            }
            results.documents.add(doc);
            long vision = doc.routing.stream().filter(s -> s.route(results.threshold).route().equals("VISION")).count();
            System.out.printf("routing %s: %d pages, %d to vision at %.2f%n", doc.id, doc.pages, vision, results.threshold);
        }
        save(results, out);

        // 2. Text path on every document, with the product's code and text model.
        Ollama.ModelInfo textInfo = ollama.modelInfo(textModel);
        results.ollamaVersion = textInfo.version();
        results.textModel = new Results.ModelRun();
        results.textModel.info = textInfo;
        if (!opts.containsKey("skip-text")) {
            for (String m : ollama.loadedVram().keySet()) ollama.unload(m);
            results.textModel.gpuBaselineMiB = GpuSampler.usedMiB();
            results.textModel.loadSeconds = ollama.load(textModel);
            results.textModel.vramBytes = ollama.loadedVram();
            TextPathRunner text = new TextPathRunner(ollama.baseUrl(), textModel, "all".equals(results.textKinds));
            try (GpuSampler gpu = new GpuSampler()) {
                for (SampleSet.Sample sample : chosen) {
                    Results.DocResult doc = find(results, sample.id());
                    TextPathRunner.Result r = text.run(Files.readAllBytes(sample.pdf()), doc.pages);
                    doc.text = new Results.TextResult();
                    doc.text.totalSeconds = r.totalSeconds();
                    doc.text.extractionSeconds = r.extractionSeconds();
                    doc.text.modelSeconds = r.modelSeconds();
                    doc.text.ocrPages = r.ocrPages();
                    doc.text.failure = r.failure();
                    doc.text.findings = r.findings();
                    if (r.failure() != null) results.textModel.errors++;
                    System.out.printf("text %s: %.1f s, %d findings%s%n", doc.id, r.totalSeconds(), r.findings().size(),
                            r.failure() == null ? "" : ", stopped: " + r.failure());
                }
                results.textModel.gpuPeakMiB = gpu.peakMiB();
            }
            ollama.unload(textModel);
            save(results, out);
        }

        // 3. Vision path on the routed pages, one model at a time.
        if (!opts.containsKey("skip-vision")) {
            for (String model : visionModels) {
                Results.ModelRun run = new Results.ModelRun();
                run.info = ollama.modelInfo(model);
                for (String m : ollama.loadedVram().keySet()) ollama.unload(m);
                run.gpuBaselineMiB = GpuSampler.usedMiB();
                run.loadSeconds = ollama.load(model);
                // The first image request also loads the vision encoder (about a minute): do it once, untimed.
                run.warmUpSeconds = warmUp(client, model, run.info.thinks());
                run.vramBytes = ollama.loadedVram();
                List<BufferedImage> probePages = new ArrayList<>();
                try (GpuSampler gpu = new GpuSampler()) {
                    for (SampleSet.Sample sample : chosen) {
                        Results.DocResult doc = find(results, sample.id());
                        List<Results.VisionPage> pages = new ArrayList<>();
                        try (PDDocument pdf = Loader.loadPDF(sample.pdf().toFile())) {
                            PDFRenderer renderer = new PDFRenderer(pdf);
                            for (PageSignals s : doc.routing) {
                                if (!s.route(results.visionThreshold).route().equals("VISION")) continue;
                                BufferedImage image = pageImage(renderer, s.page() - 1);
                                if (probePages.size() < 8) probePages.add(image);
                                pages.add(askOnce(client, run, model, image, s.page()));
                            }
                        }
                        doc.vision.put(model, pages);
                        double secs = pages.stream().mapToDouble(Results.VisionPage::seconds).sum();
                        System.out.printf("vision %s %s: %d pages, %.1f s%n", model, doc.id, pages.size(), secs);
                    }
                    run.concurrency = concurrency(client, model, run.info.thinks(), probePages);
                    run.gpuPeakMiB = gpu.peakMiB();
                }
                ollama.unload(model);
                results.visionModels.add(run);
                save(results, out);
            }
        }

        Files.writeString(out.resolve("summary.md"), Summary.markdown(results, truths), StandardCharsets.UTF_8);
        System.out.println("results and summary written to the results folder: " + out.getFileName());
    }

    static BufferedImage pageImage(PDFRenderer renderer, int index) throws IOException {
        float dpi = VisionClient.MAX_SIDE / 11f;
        return renderer.renderImageWithDPI(index, dpi, ImageType.RGB);
    }

    static double warmUp(VisionClient client, String model, boolean thinks) {
        BufferedImage blank = new BufferedImage(850, 1100, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = blank.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, 850, 1100);
        g.dispose();
        try {
            return client.ask(model, thinks, blank, 1).seconds();
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    /** A copy of the page with a few pixels changed in the top-left corner, so no cache can answer it. */
    static BufferedImage marked(BufferedImage page, int seed) {
        BufferedImage copy = new BufferedImage(page.getWidth(), page.getHeight(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = copy.createGraphics();
        g.drawImage(page, 0, 0, null);
        g.dispose();
        java.util.Random random = new java.util.Random(seed);
        for (int i = 0; i < 40; i++) copy.setRGB(random.nextInt(8), random.nextInt(8), random.nextInt(0xFFFFFF));
        return copy;
    }

    private static Results.VisionPage askOnce(VisionClient client, Results.ModelRun run, String model, BufferedImage image, int page) {
        try {
            VisionClient.Answer a = client.ask(model, run.info.thinks(), image, page);
            if (a.parseFailed()) run.errors++;
            return new Results.VisionPage(page, a.seconds(), a.parseFailed(), null, a.promptTokens(), a.outputTokens(), a.items());
        } catch (Exception e) {
            run.errors++;
            return new Results.VisionPage(page, 0, false, e.getClass().getSimpleName(), 0, 0, List.of());
        }
    }

    /** Sends 1, 2 and 4 requests at once (two rounds each) and times them. */
    static List<Results.ConcurrencyLevel> concurrency(VisionClient client, String model, boolean thinks, List<BufferedImage> pages)
            throws InterruptedException {
        List<Results.ConcurrencyLevel> levels = new ArrayList<>();
        if (pages.isEmpty()) return levels;
        for (int n : new int[]{1, 2, 4}) {
            List<Double> latencies = new ArrayList<>();
            double wall = 0;
            for (int round = 0; round < 2; round++) {
                ExecutorService pool = Executors.newFixedThreadPool(n);
                List<Future<Double>> futures = new ArrayList<>();
                long start = System.nanoTime();
                for (int i = 0; i < n; i++) {
                    // Ollama answers a repeated identical image from its cache; mark each copy so every request is new.
                    BufferedImage image = marked(pages.get((round * n + i) % pages.size()), round * 10 + n * 100 + i);
                    futures.add(pool.submit(() -> client.ask(model, thinks, image, 1).seconds()));
                }
                for (Future<Double> f : futures) {
                    try {
                        latencies.add(f.get());
                    } catch (Exception e) {
                        latencies.add(Double.NaN);
                    }
                }
                wall += (System.nanoTime() - start) / 1e9;
                pool.shutdown();
            }
            double mean = latencies.stream().filter(d -> !d.isNaN()).mapToDouble(d -> d).average().orElse(Double.NaN);
            double max = latencies.stream().filter(d -> !d.isNaN()).mapToDouble(d -> d).max().orElse(Double.NaN);
            levels.add(new Results.ConcurrencyLevel(n, mean, max, wall, 60.0 * 2 * n / wall));
            System.out.printf("concurrency %s n=%d: mean %.1f s, %.1f pages/min%n", model, n, mean, 60.0 * 2 * n / wall);
        }
        return levels;
    }

    private static Results.DocResult find(Results results, String id) {
        return results.documents.stream().filter(d -> d.id.equals(id)).findFirst().orElseThrow();
    }

    private static void save(Results results, Path out) throws IOException {
        JSON.writeValue(out.resolve("results.json").toFile(), results);
    }

    /** {@code model=PIXELS|PER_MILLE|UNIT,...}: a box convention for a model whose trained convention is not the default. */
    static Map<String, VisionClient.BoxConvention> boxOverrides(String spec) {
        Map<String, VisionClient.BoxConvention> out = new LinkedHashMap<>();
        if (spec == null || spec.isBlank()) return out;
        for (String part : spec.split(",")) {
            int eq = part.lastIndexOf('=');
            if (eq <= 0) throw new IllegalArgumentException("--boxes wants model=PIXELS|PER_MILLE|UNIT, got " + part);
            out.put(part.substring(0, eq).trim(), VisionClient.BoxConvention.valueOf(part.substring(eq + 1).trim()));
        }
        return out;
    }

    static Map<String, String> options(String[] args) {
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) throw new IllegalArgumentException("unexpected argument " + args[i]);
            String key = args[i].substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) opts.put(key, args[++i]);
            else opts.put(key, "true");
        }
        return opts;
    }

    private static String required(Map<String, String> opts, String key) {
        String v = opts.get(key);
        if (v == null) throw new IllegalArgumentException("missing --" + key);
        return v;
    }
}
