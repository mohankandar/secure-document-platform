package com.securedoc.platform.infrastructure.redaction;

import com.securedoc.platform.application.port.out.PdfRedactionPort.RedactionTarget;
import com.securedoc.platform.application.port.out.RedactionVerificationPort;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Verification for the current burn-flat V1 output.
 *
 * <p>A safe artifact must be a readable PDF with the independently recorded source page count,
 * no extractable text layer, and a visually opaque/dark interior for every expected redaction
 * target. The visual target check catches a misplaced/missed burn-flat box that a text-layer-only
 * check cannot detect.
 */
@Component
public class PdfBoxRedactionVerificationAdapter implements RedactionVerificationPort {

    private static final float VERIFY_DPI = 96f;
    private static final int DARK_CHANNEL_THRESHOLD = 45;
    private static final double REQUIRED_DARK_RATIO = 0.90;
    private static final double INTERIOR_MARGIN_RATIO = 0.15;

    @Override
    public VerificationResult verify(byte[] pdfContent,
                                     int expectedSourcePageCount,
                                     List<RedactionTarget> expectedTargets) {
        List<RedactionTarget> targets = expectedTargets == null ? List.of() : List.copyOf(expectedTargets);
        if (pdfContent == null || pdfContent.length == 0) {
            return VerificationResult.failed(0, targets.size(), 0, false, "Generated PDF is empty");
        }
        if (expectedSourcePageCount < 1) {
            return VerificationResult.failed(0, targets.size(), 0, false,
                    "Source page count is unavailable for verification");
        }

        try (PDDocument document = Loader.loadPDF(pdfContent)) {
            int pageCount = document.getNumberOfPages();
            if (pageCount != expectedSourcePageCount) {
                return VerificationResult.failed(pageCount, targets.size(), 0, false,
                        "Generated PDF page count does not match source");
            }

            String extracted = new PDFTextStripper().getText(document);
            boolean hasText = extracted != null && !extracted.isBlank();
            if (hasText) {
                return VerificationResult.failed(pageCount, targets.size(), 0, true,
                        "Generated PDF still contains an extractable text layer");
            }

            int covered = verifyTargetCoverage(document, targets);
            if (covered != targets.size()) {
                return VerificationResult.failed(pageCount, targets.size(), covered, false,
                        "One or more expected redaction regions are not visually covered");
            }

            return VerificationResult.safe(pageCount, targets.size(), covered);
        } catch (IOException e) {
            return VerificationResult.failed(0, targets.size(), 0, false,
                    "Generated PDF could not be parsed for verification");
        }
    }

    private int verifyTargetCoverage(PDDocument document, List<RedactionTarget> targets) throws IOException {
        if (targets.isEmpty()) {
            return 0;
        }

        PDFRenderer renderer = new PDFRenderer(document);
        List<BufferedImage> renderedPages = new ArrayList<>(document.getNumberOfPages());
        for (int i = 0; i < document.getNumberOfPages(); i++) {
            renderedPages.add(null);
        }

        int covered = 0;
        for (RedactionTarget target : targets) {
            int pageIndex = target.pageNumber() - 1;
            if (pageIndex < 0 || pageIndex >= renderedPages.size()) {
                continue;
            }
            BufferedImage image = renderedPages.get(pageIndex);
            if (image == null) {
                image = renderer.renderImageWithDPI(pageIndex, VERIFY_DPI);
                renderedPages.set(pageIndex, image);
            }
            if (isRegionDark(image, target)) {
                covered++;
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

        // Sample the interior to avoid anti-aliased box edges affecting the result.
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
                if (r <= DARK_CHANNEL_THRESHOLD && g <= DARK_CHANNEL_THRESHOLD && b <= DARK_CHANNEL_THRESHOLD) {
                    dark++;
                }
            }
        }
        return sampled > 0 && ((double) dark / sampled) >= REQUIRED_DARK_RATIO;
    }

    private int clamp(int value, int min, int max) {
        if (max < min) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
