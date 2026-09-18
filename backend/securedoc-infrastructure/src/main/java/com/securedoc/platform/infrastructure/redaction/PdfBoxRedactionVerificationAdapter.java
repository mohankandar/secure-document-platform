package com.securedoc.platform.infrastructure.redaction;

import com.securedoc.platform.application.port.out.PdfRedactionPort.RedactionTarget;
import com.securedoc.platform.application.port.out.RedactionVerificationPort;
import com.securedoc.platform.infrastructure.pdf.PdfBoxTempFileSupport;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Security verification for the selective V1 redaction artifact.
 *
 * <p>Untouched static pages may retain native searchable text. Every page containing an expected
 * redaction target, however, must be burn-flattened with no extractable text layer. No active
 * annotations may remain anywhere in the finished artifact. Each expected target must also be
 * visually covered by an opaque dark region. Page count is checked against independently recorded
 * source extraction metadata.
 */
@Component
public class PdfBoxRedactionVerificationAdapter implements RedactionVerificationPort {

    private static final float VERIFY_DPI = 96f;
    private static final int DARK_CHANNEL_THRESHOLD = 45;
    private static final double REQUIRED_DARK_RATIO = 0.90;
    private static final double INTERIOR_MARGIN_RATIO = 0.15;

    @Override
    public VerificationResult verify(InputStream pdfContent,
                                     int expectedSourcePageCount,
                                     List<RedactionTarget> expectedTargets) {
        List<RedactionTarget> targets = expectedTargets == null ? List.of() : List.copyOf(expectedTargets);
        if (pdfContent == null) {
            return VerificationResult.failed(0, targets.size(), 0, false, "Generated PDF is empty");
        }
        if (expectedSourcePageCount < 1) {
            return VerificationResult.failed(0, targets.size(), 0, false,
                    "Source page count is unavailable for verification");
        }

        try (PdfBoxTempFileSupport.StagedPdf staged = PdfBoxTempFileSupport.stage(pdfContent, "lexavault-redaction-verify-")) {
            PDDocument document = staged.document();
            int pageCount = document.getNumberOfPages();
            if (pageCount != expectedSourcePageCount) {
                return VerificationResult.failed(pageCount, targets.size(), 0, false,
                        "Generated PDF page count does not match source");
            }

            Set<Integer> redactedPages = targetPages(targets);
            if (hasExtractableTextOnPages(document, redactedPages)) {
                return VerificationResult.failed(pageCount, targets.size(), 0, true,
                        "A redacted page still contains an extractable text layer");
            }
            if (hasAnyAnnotations(document)) {
                return VerificationResult.failed(pageCount, targets.size(), 0, false,
                        "Generated PDF still contains annotations");
            }

            int covered = verifyTargetCoverage(document, targets);
            if (covered != targets.size()) {
                return VerificationResult.failed(pageCount, targets.size(), covered, false,
                        "One or more expected redaction regions are not visually covered");
            }

            return VerificationResult.safe(pageCount, targets.size(), covered);
        } catch (IOException | RuntimeException e) {
            return VerificationResult.failed(0, targets.size(), 0, false,
                    "Generated PDF could not be parsed for verification");
        }
    }

    private Set<Integer> targetPages(List<RedactionTarget> targets) {
        Set<Integer> pages = new LinkedHashSet<>();
        for (RedactionTarget target : targets) pages.add(target.pageNumber());
        return pages;
    }

    private boolean hasExtractableTextOnPages(PDDocument document, Set<Integer> pageNumbers) throws IOException {
        for (int pageNumber : pageNumbers) {
            if (pageNumber < 1 || pageNumber > document.getNumberOfPages()) return true;
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(pageNumber);
            stripper.setEndPage(pageNumber);
            String extracted = stripper.getText(document);
            if (extracted != null && !extracted.isBlank()) return true;
        }
        return false;
    }

    private boolean hasAnyAnnotations(PDDocument document) throws IOException {
        for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
            if (!document.getPage(pageIndex).getAnnotations().isEmpty()) return true;
        }
        return false;
    }

    /** Render one target page at a time so verification memory is page-bounded. */
    private int verifyTargetCoverage(PDDocument document, List<RedactionTarget> targets) throws IOException {
        if (targets.isEmpty()) return 0;

        Map<Integer, List<RedactionTarget>> targetsByPage = new LinkedHashMap<>();
        for (RedactionTarget target : targets) {
            targetsByPage.computeIfAbsent(target.pageNumber(), ignored -> new java.util.ArrayList<>()).add(target);
        }

        PDFRenderer renderer = new PDFRenderer(document);
        int covered = 0;
        for (Map.Entry<Integer, List<RedactionTarget>> entry : targetsByPage.entrySet()) {
            int pageIndex = entry.getKey() - 1;
            if (pageIndex < 0 || pageIndex >= document.getNumberOfPages()) continue;
            BufferedImage image = renderer.renderImageWithDPI(pageIndex, VERIFY_DPI);
            try {
                for (RedactionTarget target : entry.getValue()) {
                    if (isRegionDark(image, target)) covered++;
                }
            } finally {
                image.flush();
            }
        }
        return covered;
    }

    private boolean isRegionDark(BufferedImage image, RedactionTarget target) {
        int imageWidth = image.getWidth();
        int imageHeight = image.getHeight();

        double left = target.left();
        double top = target.top();
        double width = target.width();
        double height = target.height();

        double marginX = width * INTERIOR_MARGIN_RATIO;
        double marginY = height * INTERIOR_MARGIN_RATIO;
        double sampleLeft = left + marginX;
        double sampleTop = top + marginY;
        double sampleWidth = Math.max(width - (2 * marginX), width * 0.50);
        double sampleHeight = Math.max(height - (2 * marginY), height * 0.50);

        int x0 = clamp((int) Math.floor(sampleLeft * imageWidth), 0, imageWidth - 1);
        int y0 = clamp((int) Math.floor(sampleTop * imageHeight), 0, imageHeight - 1);
        int x1 = clamp((int) Math.ceil((sampleLeft + sampleWidth) * imageWidth), x0 + 1, imageWidth);
        int y1 = clamp((int) Math.ceil((sampleTop + sampleHeight) * imageHeight), y0 + 1, imageHeight);

        long sampled = 0;
        long dark = 0;
        int stepX = Math.max(1, (x1 - x0) / 40);
        int stepY = Math.max(1, (y1 - y0) / 20);
        for (int y = y0; y < y1; y += stepY) {
            for (int x = x0; x < x1; x += stepX) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                sampled++;
                if (r <= DARK_CHANNEL_THRESHOLD && g <= DARK_CHANNEL_THRESHOLD && b <= DARK_CHANNEL_THRESHOLD) dark++;
            }
        }
        return sampled > 0 && ((double) dark / sampled) >= REQUIRED_DARK_RATIO;
    }

    private int clamp(int value, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, value));
    }
}
