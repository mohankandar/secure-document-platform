package com.securedoc.benchmark;

import com.securedoc.benchmark.textpath.TextPathRunner;
import com.securedoc.benchmark.truth.Truth;
import com.securedoc.benchmark.vision.GpuSampler;
import com.securedoc.benchmark.vision.Ollama;
import com.securedoc.benchmark.vision.VisionClient;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Task 0040 probe, without the full benchmark run:
 * <ul>
 *   <li>requests at once (1, 2, 4) per vision model, on the pages the answer files mark as VISION;</li>
 *   <li>with {@code --with-text}: the text model and one vision model loaded at the same time, one text
 *       request (the product's text path on one typed page) and one vision request alone and at once.</li>
 * </ul>
 * {@code --label} records how the Ollama server was started (the harness cannot read the server's settings).
 * Output: {@code results/probe-<time>/probe.json} and {@code probe.md}. Ids, numbers and seconds only.
 */
final class Probe {

    public static final class ProbeResults {
        public String date;
        public String gpu;
        public String ollamaVersion;
        public String serverSetting;
        public String promptVersion;
        public String promptSha256;
        public List<ModelProbe> models = new ArrayList<>();
        public CoResident coResident;
    }

    public static final class ModelProbe {
        public Ollama.ModelInfo info;
        public long vramMiB;
        public int gpuPeakMiB;
        public List<Results.ConcurrencyLevel> concurrency = new ArrayList<>();
    }

    public static final class CoResident {
        public String textModel;
        public String visionModel;
        public Map<String, Long> vramMiB = new LinkedHashMap<>();
        public int gpuUsedMiB;
        public int gpuPeakMiB;
        public List<Round> rounds = new ArrayList<>();
    }

    public record Round(double textAlone, double visionAlone, double textTogether, double visionTogether, double wallTogether) {
    }

    private Probe() {
    }

    static void run(Map<String, String> opts) throws Exception {
        Path root = Path.of(opts.get("root"));
        String set = opts.getOrDefault("set", "public");
        Ollama ollama = new Ollama(opts.getOrDefault("ollama", "http://localhost:11434"));
        String prompt = opts.getOrDefault("prompt", VisionClient.DEFAULT_PROMPT);
        VisionClient client = new VisionClient(ollama, prompt, BenchmarkMain.boxOverrides(opts.get("boxes")));
        List<String> models = List.of(opts.getOrDefault("models", "qwen3-vl:8b-instruct").split(","));

        ProbeResults out = new ProbeResults();
        out.date = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        out.gpu = GpuSampler.gpuName();
        out.serverSetting = opts.getOrDefault("label", "not given");
        out.promptVersion = VisionClient.promptName(prompt);
        out.promptSha256 = VisionClient.promptSha256(prompt);
        Path folder = root.resolve("results").resolve("probe-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        Files.createDirectories(folder);

        SampleSet samples = SampleSet.load(root, set);
        List<BufferedImage> pages = visionPages(samples, 8);
        if (pages.isEmpty()) throw new IOException("no page marked VISION in the answer files");

        for (String model : models) {
            ModelProbe p = new ModelProbe();
            p.info = ollama.modelInfo(model);
            out.ollamaVersion = p.info.version();
            for (String m : ollama.loadedVram().keySet()) ollama.unload(m);
            ollama.load(model);
            BenchmarkMain.warmUp(client, model, p.info.thinks());
            p.vramMiB = ollama.loadedVram().getOrDefault(model, 0L) / (1024 * 1024);
            try (GpuSampler gpu = new GpuSampler()) {
                p.concurrency = BenchmarkMain.concurrency(client, model, p.info.thinks(), pages);
                p.gpuPeakMiB = gpu.peakMiB();
            }
            ollama.unload(model);
            out.models.add(p);
            save(out, folder);
        }

        String textModel = opts.get("with-text");
        if (textModel != null) {
            String visionModel = opts.getOrDefault("with-vision", models.get(0));
            out.coResident = coResident(ollama, client, samples, pages, textModel, visionModel, 3);
            save(out, folder);
        }
        Files.writeString(folder.resolve("probe.md"), markdown(out), StandardCharsets.UTF_8);
        System.out.println("probe written to the results folder: " + folder.getFileName());
    }

    static CoResident coResident(Ollama ollama, VisionClient client, SampleSet samples, List<BufferedImage> pages,
                                 String textModel, String visionModel, int rounds) throws Exception {
        CoResident c = new CoResident();
        c.textModel = textModel;
        c.visionModel = visionModel;
        boolean thinks = ollama.modelInfo(visionModel).thinks();
        byte[] textPage = typedPage(samples);
        TextPathRunner text = new TextPathRunner(ollama.baseUrl(), textModel);

        for (String m : ollama.loadedVram().keySet()) ollama.unload(m);
        ollama.load(textModel);
        ollama.load(visionModel);
        BenchmarkMain.warmUp(client, visionModel, thinks);
        text.run(textPage, 1); // untimed warm-up of the text model and the extraction code
        Map<String, Long> vram = ollama.loadedVram();
        vram.forEach((k, v) -> c.vramMiB.put(k, v / (1024 * 1024)));
        if (!vram.containsKey(textModel) || !vram.containsKey(visionModel)) {
            throw new IOException("both models must be resident; Ollama holds " + vram.keySet());
        }
        c.gpuUsedMiB = GpuSampler.usedMiB();
        try (GpuSampler gpu = new GpuSampler()) {
            for (int i = 0; i < rounds; i++) {
                double tAlone = text.run(textPage, 1).totalSeconds();
                double vAlone = client.ask(visionModel, thinks, BenchmarkMain.marked(pages.get(i % pages.size()), 7000 + i), 1).seconds();
                BufferedImage image = BenchmarkMain.marked(pages.get((i + 1) % pages.size()), 8000 + i);
                long start = System.nanoTime();
                CompletableFuture<Double> t = CompletableFuture.supplyAsync(() -> text.run(textPage, 1).totalSeconds());
                CompletableFuture<Double> v = CompletableFuture.supplyAsync(() -> {
                    try {
                        return client.ask(visionModel, thinks, image, 1).seconds();
                    } catch (Exception e) {
                        return Double.NaN;
                    }
                });
                double tTogether = t.get(), vTogether = v.get();
                double wall = (System.nanoTime() - start) / 1e9;
                c.rounds.add(new Round(tAlone, vAlone, tTogether, vTogether, wall));
                System.out.printf(Locale.ROOT, "co-resident round %d: text %.1f s alone, %.1f s together; vision %.1f s alone, %.1f s together%n",
                        i + 1, tAlone, tTogether, vAlone, vTogether);
            }
            c.gpuPeakMiB = gpu.peakMiB();
        }
        ollama.unload(visionModel);
        ollama.unload(textModel);
        return c;
    }

    /** Up to {@code max} pages the answer files mark as VISION, rendered as the vision path renders them. */
    static List<BufferedImage> visionPages(SampleSet samples, int max) throws IOException {
        List<BufferedImage> out = new ArrayList<>();
        for (SampleSet.Sample s : samples.samples()) {
            try (PDDocument pdf = Loader.loadPDF(s.pdf().toFile())) {
                PDFRenderer renderer = new PDFRenderer(pdf);
                for (Truth.PageTruth p : s.truth().pages()) {
                    if (out.size() >= max) return out;
                    if ("VISION".equals(p.expectedRoute())) out.add(BenchmarkMain.pageImage(renderer, p.page() - 1));
                }
            }
        }
        return out;
    }

    /** The first fast-path page with an SSN or date of birth, as a one-page PDF: one text-model request. */
    static byte[] typedPage(SampleSet samples) throws IOException {
        Set<String> wanted = Set.of("SSN", "DATE_OF_BIRTH");
        for (SampleSet.Sample s : samples.samples()) {
            for (Truth.PageTruth p : s.truth().pages()) {
                if (!"FAST".equals(p.expectedRoute())) continue;
                if (p.items().stream().noneMatch(i -> wanted.contains(i.type()))) continue;
                try (PDDocument pdf = Loader.loadPDF(s.pdf().toFile()); PDDocument one = new PDDocument()) {
                    one.importPage(pdf.getPage(p.page() - 1));
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    one.save(bytes);
                    return bytes.toByteArray();
                }
            }
        }
        throw new IOException("no fast-path page with an SSN or date of birth in the answer files");
    }

    private static void save(ProbeResults out, Path folder) throws IOException {
        BenchmarkMain.JSON.writeValue(folder.resolve("probe.json").toFile(), out);
    }

    static String markdown(ProbeResults r) {
        StringBuilder md = new StringBuilder("# Probe: requests at once\n\n");
        md.append("Run ").append(r.date).append(". GPU: ").append(r.gpu).append(". Ollama ").append(r.ollamaVersion)
                .append(". Server setting: ").append(r.serverSetting).append(". Prompt ").append(r.promptVersion).append(".\n\n");
        md.append("| Model | Digest | Model VRAM MiB | GPU peak MiB | At once | Mean latency s | Max latency s | Pages per minute |\n|---|---|---|---|---|---|---|---|\n");
        for (ModelProbe p : r.models) {
            for (Results.ConcurrencyLevel c : p.concurrency) {
                md.append(String.format(Locale.ROOT, "| %s | %s | %d | %d | %d | %.2f | %.2f | %.1f |%n", p.info.name(), p.info.shortId(),
                        p.vramMiB, p.gpuPeakMiB, c.requests(), c.meanLatencySeconds(), c.maxLatencySeconds(), c.pagesPerMinute()));
            }
        }
        if (r.coResident != null) {
            CoResident c = r.coResident;
            md.append("\n## Text and vision model resident together\n\n").append(c.textModel).append(" + ").append(c.visionModel)
                    .append(". VRAM per model (MiB): ").append(c.vramMiB).append(". GPU used with both loaded: ").append(c.gpuUsedMiB)
                    .append(" MiB, peak ").append(c.gpuPeakMiB).append(" MiB.\n\n");
            md.append("| Round | Text alone s | Vision alone s | Text at once s | Vision at once s | Wall at once s |\n|---|---|---|---|---|---|\n");
            int n = 0;
            for (Round x : c.rounds) {
                md.append(String.format(Locale.ROOT, "| %d | %.2f | %.2f | %.2f | %.2f | %.2f |%n", ++n, x.textAlone(), x.visionAlone(),
                        x.textTogether(), x.visionTogether(), x.wallTogether()));
            }
        }
        return md.toString();
    }
}
