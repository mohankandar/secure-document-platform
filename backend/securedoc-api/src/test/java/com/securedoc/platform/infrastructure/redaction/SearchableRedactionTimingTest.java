package com.securedoc.platform.infrastructure.redaction;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.securedoc.platform.application.port.out.PdfRedactionPort.RedactionOptions;
import com.securedoc.platform.application.port.out.PdfRedactionPort.RedactionOutput;
import com.securedoc.platform.application.port.out.PdfRedactionPort.RedactionTarget;
import com.securedoc.platform.application.port.out.RedactionVerificationPort.RedactedValues;
import com.securedoc.platform.infrastructure.extraction.HybridPdfTextExtractionAdapter;
import com.securedoc.platform.infrastructure.extraction.PdfBoxTextExtractionAdapter;
import com.securedoc.platform.infrastructure.ocr.TesseractOcrAdapter;
import com.securedoc.platform.infrastructure.ocr.TesseractOcrProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0044 timing, on request only: {@code LEXAVAULT_TIMING_PDF} names a sample PDF with its answer file beside it
 * ({@code X.truth.json}, the benchmark's format: boxes only, never a value). Each answer-file item becomes a redaction
 * target. Prints seconds and sizes for searchable off and on, the final check, and the second text extraction the
 * redaction step now does. Numbers only.
 */
@EnabledIfEnvironmentVariable(named = "LEXAVAULT_TIMING_PDF", matches = ".+")
class SearchableRedactionTimingTest {

    private static final List<String> TEXT_TYPES = List.of("SSN", "DATE_OF_BIRTH", "CREDIT_CARD_NUMBER", "CREDIT_SCORE",
            "BANK_ACCOUNT", "ROUTING_NUMBER", "GOVERNMENT_ID");

    @Test
    void secondsPerRedactedPage() throws Exception {
        Path pdf = Path.of(System.getenv("LEXAVAULT_TIMING_PDF"));
        byte[] source = Files.readAllBytes(pdf);
        JsonNode truth = JsonMapper.builderWithJackson2Defaults().build().readTree(pdf.resolveSibling(
                pdf.getFileName().toString().replaceAll("\\.pdf$", ".truth.json")).toFile());
        List<RedactionTarget> targets = new ArrayList<>();
        int pages = truth.get("pages").size();
        for (JsonNode page : truth.get("pages")) {
            for (JsonNode item : page.get("items")) {
                if (!TEXT_TYPES.contains(item.get("type").asString())) continue;
                JsonNode b = item.get("box");
                double l = b.get(0).asDouble(), t = b.get(1).asDouble();
                double w = Math.min(b.get(2).asDouble(), 1 - l), h = Math.min(b.get(3).asDouble(), 1 - t);
                if (w > 0 && h > 0) targets.add(RedactionTarget.of(page.get("page").asInt(), l, t, w, h));
            }
        }
        long redactedPages = targets.stream().map(RedactionTarget::pageNumber).distinct().count();
        assertTrue(redactedPages > 0);

        TesseractOcrProperties ocrProperties = new TesseractOcrProperties();
        PdfBoxBurnFlatRedactionAdapter engine = new PdfBoxBurnFlatRedactionAdapter(new TesseractOcrAdapter(ocrProperties));
        PdfBoxRedactionVerificationAdapter verification = new PdfBoxRedactionVerificationAdapter();
        RedactedValues probe = RedactedValues.of(List.of("zz-not-on-any-page-0044"));

        redactAndVerify(engine, verification, source, targets, pages, RedactionOptions.IMAGE_ONLY, probe); // warm-up
        double[] off = redactAndVerify(engine, verification, source, targets, pages, RedactionOptions.IMAGE_ONLY, probe);
        double[] on = redactAndVerify(engine, verification, source, targets, pages, RedactionOptions.SEARCHABLE, probe);

        HybridPdfTextExtractionAdapter extractor = new HybridPdfTextExtractionAdapter(new PdfBoxTextExtractionAdapter(),
                new TesseractOcrAdapter(ocrProperties), ocrProperties);
        long t = System.nanoTime();
        var everyPage = extractor.extract(new ByteArrayInputStream(source), "application/pdf");
        double extraction = (System.nanoTime() - t) / 1e9;
        // Task 0047: the redaction step now reads again only the pages with findings.
        java.util.Set<Integer> withFindings = new java.util.TreeSet<>();
        for (RedactionTarget target : targets) withFindings.add(target.pageNumber());
        t = System.nanoTime();
        var onlyThose = extractor.extractPages(new ByteArrayInputStream(source), "application/pdf", withFindings);
        double pageExtraction = (System.nanoTime() - t) / 1e9;
        for (var page : onlyThose.pages()) {
            assertTrue(page.text().equals(everyPage.pages().get(page.pageNumber() - 1).text()),
                    "page " + page.pageNumber() + " reads the same either way");
        }
        assertTrue(onlyThose.pageCount() == withFindings.size());

        System.out.println(String.format(Locale.ROOT,
                "TIMING_0044 pages=%d redactedPages=%d targets=%d | off: redact=%.2fs verify=%.2fs size=%d | "
                        + "on: redact=%.2fs verify=%.2fs size=%d | addedPerRedactedPage=%.2fs (redact+verify) | "
                        + "secondTextExtraction=%.2fs | sizeChange=%+.1f%%",
                pages, redactedPages, targets.size(), off[0], off[1], (long) off[2], on[0], on[1], (long) on[2],
                ((on[0] + on[1]) - (off[0] + off[1])) / redactedPages, extraction, 100.0 * (on[2] - off[2]) / off[2]));
        System.out.println(String.format(Locale.ROOT,
                "TIMING_0047 secondTextExtraction everyPage=%.2fs (%d pages, %d OCR) pagesWithFindings=%.2fs (%d pages, %d OCR)",
                extraction, everyPage.pageCount(), everyPage.ocrPageCount(),
                pageExtraction, onlyThose.pageCount(), onlyThose.ocrPageCount()));
    }

    private static double[] redactAndVerify(PdfBoxBurnFlatRedactionAdapter engine, PdfBoxRedactionVerificationAdapter v,
                                            byte[] source, List<RedactionTarget> targets, int pages,
                                            RedactionOptions options, RedactedValues values) throws Exception {
        long t0 = System.nanoTime();
        RedactionOutput out = engine.redact(new ByteArrayInputStream(source), targets, options);
        double redact = (System.nanoTime() - t0) / 1e9;
        try (var content = out.redactedPdfContent(); InputStream in = content.openStream()) {
            byte[] bytes = in.readAllBytes();
            long t1 = System.nanoTime();
            boolean safe = v.verify(new ByteArrayInputStream(bytes), pages, targets, options.searchableText(), values).safe();
            double verify = (System.nanoTime() - t1) / 1e9;
            assertTrue(safe);
            return new double[]{redact, verify, bytes.length};
        }
    }
}
