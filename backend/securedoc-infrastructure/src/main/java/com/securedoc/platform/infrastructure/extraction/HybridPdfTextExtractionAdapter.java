package com.securedoc.platform.infrastructure.extraction;

import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.port.out.OcrPort;
import com.securedoc.platform.application.port.out.TextExtractionPort;
import com.securedoc.platform.infrastructure.ocr.OcrProcessingException;
import com.securedoc.platform.infrastructure.ocr.TesseractOcrProperties;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import com.securedoc.platform.infrastructure.pdf.PdfBoxTempFileSupport;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Unit 17 normalization adapter for PDFs.
 *
 * <p>Each page prefers trusted embedded PDF text when it is meaningfully usable.
 * Sparse/image-only pages are rendered and sent through the provider-neutral OCR
 * port. Native and OCR pages are normalized into the same {@link PageText}/{@link TextSpan}
 * representation, so the approved Unit 16 AI-first pipeline remains unchanged.
 */
@Component
@Primary
public class HybridPdfTextExtractionAdapter implements TextExtractionPort {

    private static final String PDF_MIME_TYPE = "application/pdf";

    private final PdfBoxTextExtractionAdapter nativeExtractor;
    private final OcrPort ocrPort;
    private final TesseractOcrProperties properties;

    public HybridPdfTextExtractionAdapter(PdfBoxTextExtractionAdapter nativeExtractor,
                                          OcrPort ocrPort,
                                          TesseractOcrProperties properties) {
        this.nativeExtractor = Objects.requireNonNull(nativeExtractor);
        this.ocrPort = Objects.requireNonNull(ocrPort);
        this.properties = Objects.requireNonNull(properties);
    }

    @Override
    public TextExtractionOutput extract(InputStream content, String mimeType) {
        if (!PDF_MIME_TYPE.equals(mimeType)) {
            return nativeExtractor.extract(content, mimeType);
        }

        try (PdfBoxTempFileSupport.StagedPdf staged = PdfBoxTempFileSupport.stage(content, "lexavault-hybrid-text-")) {
            PDDocument document = staged.document();
            TextExtractionOutput nativeOutput = nativeExtractor.extractDocument(document);
            PDFRenderer renderer = new PDFRenderer(document);
            List<PageText> normalizedPages = new ArrayList<>(document.getNumberOfPages());
            boolean hasEmbeddedText = false;
            boolean usedOcr = false;
            long totalChars = 0;

            for (int index = 0; index < document.getNumberOfPages(); index++) {
                int pageNumber = index + 1;
                PageText nativePage = nativePage(nativeOutput.pages(), pageNumber);

                if (nativePage != null && !nativePage.text().isBlank()) {
                    hasEmbeddedText = true;
                }

                if (hasMeaningfulNativeText(nativePage)
                        && !shouldOcrSparseNativePage(renderer, index, nativePage)) {
                    normalizedPages.add(nativePage);
                    totalChars += nativePage.text().length();
                    continue;
                }

                PageText ocrPage = recognizePage(document.getPage(index), renderer, index, pageNumber);
                normalizedPages.add(ocrPage);
                totalChars += ocrPage.text().length();
                usedOcr = true;
            }

            return new TextExtractionOutput(normalizedPages, totalChars, hasEmbeddedText, usedOcr);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to normalize PDF text", e);
        }
    }

    private PageText recognizePage(PDPage page, PDFRenderer renderer,
                                   int pageIndex, int pageNumber) throws IOException {
        int dpi = Math.max(72, properties.getRenderDpi());
        BufferedImage image = renderer.renderImageWithDPI(pageIndex, dpi, ImageType.RGB);
        try {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", png)) {
                throw new OcrProcessingException(TechnicalFailureCode.OCR_PROCESS_FAILED, false, "Unable to render PDF page for OCR");
            }

            OcrPort.OcrPageOutput output = ocrPort.recognize(new OcrPort.OcrPageInput(
                    pageNumber, png.toByteArray(), "image/png", image.getWidth(), image.getHeight()));

            boolean visuallyBlank = isVisuallyBlank(image);
            if (output.text().isBlank() && !visuallyBlank) {
                throw new OcrProcessingException(
                        TechnicalFailureCode.OCR_PROCESS_FAILED, false,
                        "OCR produced no readable text for a non-blank page");
            }
            if (!output.text().isBlank()
                    && output.confidence() < clamp01(properties.getMinimumPageConfidence())) {
                throw new OcrProcessingException(
                        TechnicalFailureCode.OCR_PROCESS_FAILED, false,
                        "OCR confidence is below the configured minimum");
            }

            PDRectangle mediaBox = page.getMediaBox();
            int pageWidth = Math.round(mediaBox.getWidth());
            int pageHeight = Math.round(mediaBox.getHeight());
            List<TextSpan> spans = output.spans().stream()
                    .map(span -> new TextSpan(
                            span.startOffset(), span.endOffset(),
                            span.left(), span.top(), span.width(), span.height(), span.confidence()))
                    .toList();

            var visualRegions = SolidDarkRectangleDetector.detect(image);
            return new PageText(pageNumber, pageWidth, pageHeight,
                    output.text(), spans, visualRegions, ExtractionSource.OCR, output.confidence());
        } finally {
            image.flush();
        }
    }

    private boolean hasMeaningfulNativeText(PageText page) {
        if (page == null || page.text().isBlank() || page.spans().isEmpty()) {
            return false;
        }
        long nonWhitespace = page.text().codePoints().filter(cp -> !Character.isWhitespace(cp)).count();
        return nonWhitespace >= Math.max(1, properties.getMinNativeCharacters());
    }

    /**
     * Detects the mixed-page case where a PDF has enough embedded text to pass the normal native
     * threshold (for example a title/footer) but the meaningful customer data is actually inside
     * a large raster image. We inspect only sparse native pages and only at low DPI, keeping the
     * common fully-digital path fast while preventing scanned appendices from bypassing OCR.
     */
    private boolean shouldOcrSparseNativePage(PDFRenderer renderer,
                                              int pageIndex,
                                              PageText nativePage) throws IOException {
        long nonWhitespace = nativePage.text().codePoints()
                .filter(cp -> !Character.isWhitespace(cp))
                .count();
        int maxCharacters = Math.max(
                properties.getMinNativeCharacters(),
                properties.getSparseNativeImageCheckMaxCharacters());
        if (nonWhitespace > maxCharacters) {
            return false;
        }

        int inspectionDpi = Math.max(36, properties.getSparseNativeImageCheckDpi());
        BufferedImage preview = renderer.renderImageWithDPI(pageIndex, inspectionDpi, ImageType.GRAY);
        try {
            double darkRatio = darkPixelRatio(preview);
            return darkRatio >= clamp01(properties.getSparseNativeImageDarkPixelRatio());
        } finally {
            preview.flush();
        }
    }

    /**
     * Fail closed for non-blank image pages that OCR cannot read, while allowing
     * genuinely blank pages to continue through the no-finding safe-output path.
     */
    private boolean isVisuallyBlank(BufferedImage image) {
        return darkPixelRatio(image) <= Math.max(0.0, properties.getBlankPageDarkPixelRatio());
    }

    private double darkPixelRatio(BufferedImage image) {
        long sampled = 0;
        long dark = 0;
        int step = Math.max(1, Math.min(image.getWidth(), image.getHeight()) / 500);
        for (int y = 0; y < image.getHeight(); y += step) {
            for (int x = 0; x < image.getWidth(); x += step) {
                int rgb = image.getRGB(x, y);
                int red = (rgb >> 16) & 0xff;
                int green = (rgb >> 8) & 0xff;
                int blue = rgb & 0xff;
                int luminance = (red * 2126 + green * 7152 + blue * 722) / 10000;
                if (luminance < 245) {
                    dark++;
                }
                sampled++;
            }
        }
        if (sampled == 0) {
            return 0.0;
        }
        return (double) dark / sampled;
    }

    private static double clamp01(double value) {
        if (value < 0.0) return 0.0;
        if (value > 1.0) return 1.0;
        return value;
    }

    private static PageText nativePage(List<PageText> pages, int pageNumber) {
        if (pages == null || pageNumber < 1 || pageNumber > pages.size()) {
            return null;
        }
        PageText candidate = pages.get(pageNumber - 1);
        return candidate.pageNumber() == pageNumber
                ? candidate
                : pages.stream().filter(page -> page.pageNumber() == pageNumber).findFirst().orElse(null);
    }
}
