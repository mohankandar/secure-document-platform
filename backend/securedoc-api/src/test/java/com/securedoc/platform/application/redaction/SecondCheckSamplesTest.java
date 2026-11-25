package com.securedoc.platform.application.redaction;

import com.securedoc.platform.application.port.out.PdfRedactionPort.RedactionOptions;
import com.securedoc.platform.application.port.out.PdfRedactionPort.RedactionTarget;
import com.securedoc.platform.application.port.out.TextExtractionPort.PageText;
import com.securedoc.platform.ai.analysis.OllamaSensitiveContentAnalysisAdapter;
import com.securedoc.platform.ai.analysis.OllamaVisionModelAdapter;
import com.securedoc.platform.application.secondcheck.SecondCheck;
import com.securedoc.platform.domain.business.PageBatchSettings;
import com.securedoc.platform.domain.business.RedactionCatalog;
import com.securedoc.platform.domain.business.ReviewMode;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.business.VisionSettings;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.extraction.HybridPdfTextExtractionAdapter;
import com.securedoc.platform.infrastructure.extraction.PdfBoxTextExtractionAdapter;
import com.securedoc.platform.infrastructure.extraction.PdfPageImageAdapter;
import com.securedoc.platform.infrastructure.ocr.TesseractOcrAdapter;
import com.securedoc.platform.infrastructure.ocr.TesseractOcrProperties;
import com.securedoc.platform.infrastructure.redaction.PdfBoxBurnFlatRedactionAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Task 0083, not a test of the build: makes finished files of the benchmark's synthetic samples with the product's own
 * redaction, with one item per document left visible on purpose, for test-tools/second-check/measure.mjs. Runs only with
 * LEXAVAULT_SECOND_CHECK_SAMPLES (the samples) and LEXAVAULT_SECOND_CHECK_OUT (a folder outside the repository). The page
 * texts and images written there hold fake values. With LEXAVAULT_SECOND_CHECK_VISION_DIGEST (the approved vision model's
 * digest) the product's own check also runs on each file with the real models, and prints kinds and pages only.
 */
@EnabledIfEnvironmentVariable(named = "LEXAVAULT_SECOND_CHECK_SAMPLES", matches = ".+")
class SecondCheckSamplesTest {

    @Test
    void makeFinishedFiles() throws Exception {
        Path samples = Path.of(System.getenv("LEXAVAULT_SECOND_CHECK_SAMPLES"));
        Path out = Files.createDirectories(Path.of(System.getenv("LEXAVAULT_SECOND_CHECK_OUT")));
        JsonMapper json = JsonMapper.builder().build();
        TesseractOcrProperties ocrProperties = new TesseractOcrProperties();
        TesseractOcrAdapter ocr = new TesseractOcrAdapter(ocrProperties);
        var redaction = new PdfBoxBurnFlatRedactionAdapter(ocr);
        var extraction = new HybridPdfTextExtractionAdapter(new PdfBoxTextExtractionAdapter(), ocr, ocrProperties);
        var images = new PdfPageImageAdapter();
        String digest = System.getenv("LEXAVAULT_SECOND_CHECK_VISION_DIGEST");
        TenantId tenant = TenantId.generate();
        // Every kind on, as in the measurement. The settings of the local profile.
        TenantRedactionPolicy policy = new TenantRedactionPolicy(tenant, RedactionCatalog.kinds(), 0.8,
                ReviewMode.UNCERTAIN_ONLY, true, Instant.now(), true, PageBatchSettings.DEFAULTS, Set.of(), Set.of(), true,
                VisionSettings.DEFAULTS, true);
        SecondCheck check = digest == null ? null : new SecondCheck(extraction,
                new OllamaSensitiveContentAnalysisAdapter("http://localhost:11434", "qwen3.5:9b", Duration.ofSeconds(120),
                        1, 2, Duration.ofMillis(500), "10m", true, false, 8192, 2048, 2, 14_000), images,
                new OllamaVisionModelAdapter("http://localhost:11434", "qwen3-vl:8b-instruct", digest,
                        Duration.ofSeconds(120), "10m", OllamaVisionModelAdapter.SECOND_CHECK_VERSION));
        int caught = 0;
        int left1 = 0;
        int flaggedOther = 0;
        int pagesChecked = 0;
        long checkMillis = 0;
        List<Map<String, Object>> documents = new ArrayList<>();
        // Which item is left visible: another one with each variant.
        int number = Integer.parseInt(System.getenv().getOrDefault("LEXAVAULT_SECOND_CHECK_VARIANT", "0"));
        for (int d = 1; d <= 25; d++) {
            String id = String.format("public-%02d", d);
            JsonNode truth = json.readTree(Files.readAllBytes(samples.resolve(id + ".truth.json")));
            record Item(int page, String type, double[] box) { }
            List<Item> items = new ArrayList<>();
            for (JsonNode page : truth.path("pages")) {
                for (JsonNode item : page.path("items")) {
                    double[] box = new double[4];
                    for (int i = 0; i < 4; i++) box[i] = item.path("box").get(i).asDouble();
                    items.add(new Item(page.path("page").asInt(), item.path("type").asString(), box));
                }
            }
            // A value, not a signature: every document with one, another kind each time.
            List<Item> values = items.stream().filter(i -> !i.type().equals("SIGNATURE")).toList();
            Item left = values.isEmpty() ? null : values.get(number++ % values.size());
            List<RedactionTarget> targets = new ArrayList<>();
            Set<Integer> redactedPages = new LinkedHashSet<>();
            for (Item item : items) {
                if (item == left) continue;
                double l = Math.max(0, item.box()[0] - 0.004);
                double t = Math.max(0, item.box()[1] - 0.004);
                targets.add(RedactionTarget.of(item.page(), l, t, Math.min(1 - l, item.box()[2] + 0.008),
                        Math.min(1 - t, item.box()[3] + 0.008)));
                redactedPages.add(item.page());
            }
            final byte[] finished;
            var result = redaction.redact(new ByteArrayInputStream(Files.readAllBytes(samples.resolve(id + ".pdf"))), targets,
                    RedactionOptions.SEARCHABLE);
            try (var content = result.redactedPdfContent(); var stream = content.openStream()) {
                finished = stream.readAllBytes();
            }
            if (check != null) {
                long started = System.nanoTime();
                SecondCheck.Result found = check.run(tenant, DocumentId.generate(), policy,
                        () -> new ByteArrayInputStream(finished), targets, List.of(), List.of());
                checkMillis += (System.nanoTime() - started) / 1_000_000;
                pagesChecked += found.pagesChecked();
                if (left != null) left1++;
                boolean flagged = left != null && found.pagesToCheck().stream()
                        .anyMatch(f -> f.location().pageNumber() == left.page());
                if (flagged) caught++;
                flaggedOther += found.pagesToCheck().size() - (flagged ? 1 : 0);
                System.out.println("SECOND_CHECK " + id + " left=" + (left == null ? "-" : "p" + left.page() + ":"
                        + left.type()) + " " + found.auditDetail().replaceAll(", aiModels.*", ""));
            }
            List<Map<String, Object>> pages = new ArrayList<>();
            for (PageText text : extraction.extractReadable(new ByteArrayInputStream(finished), "application/pdf", null).pages()) {
                Map<String, Object> page = new LinkedHashMap<>();
                page.put("page", text.pageNumber());
                page.put("text", text.text());
                page.put("source", text.source().name());
                page.put("redacted", redactedPages.contains(text.pageNumber()));
                String png = id + "-p" + text.pageNumber() + ".png";
                Files.write(out.resolve(png), images.render(new ByteArrayInputStream(finished), text.pageNumber(), 1400).png());
                page.put("image", png);
                pages.add(page);
            }
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("id", id);
            document.put("left", left == null ? null : Map.of("page", left.page(), "type", left.type()));
            document.put("pages", pages);
            documents.add(document);
        }
        if (check != null) {
            System.out.println("SECOND_CHECK TOTAL pages with the item flagged " + caught + " of " + left1
                    + ", other pages flagged " + flaggedOther + ", pages " + pagesChecked + ", ms a page "
                    + checkMillis / Math.max(1, pagesChecked));
        }
        Files.write(out.resolve("finished.json"), json.writeValueAsBytes(documents));
    }
}
