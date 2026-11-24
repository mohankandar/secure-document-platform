package com.securedoc.platform.infrastructure.extraction;

import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.port.out.OcrPort;
import com.securedoc.platform.domain.vision.PageRoute;
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
import java.util.Set;

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
        return extract(content, mimeType, null, null, false).text();
    }

    /**
     * Task 0047: only the given pages, so the redaction step's final check does not OCR pages without findings again.
     * Each page goes through the same steps as in {@link #extract(InputStream, String)} (native text, the sparse-page
     * image check, OCR), so its text and offsets are the ones detection used.
     */
    @Override
    public TextExtractionOutput extractPages(InputStream content, String mimeType, Set<Integer> pageNumbers) {
        return extract(content, mimeType, Set.copyOf(pageNumbers), null, false).text();
    }

    @Override
    public RoutedOutput extractRouted(InputStream content, String mimeType, Set<Integer> pageNumbers,
                                      double ocrThreshold) {
        return extract(content, mimeType, pageNumbers == null ? null : Set.copyOf(pageNumbers), ocrThreshold, true);
    }

    @Override
    public TextExtractionOutput extractReadable(InputStream content, String mimeType, Set<Integer> pageNumbers) {
        return extract(content, mimeType, pageNumbers == null ? null : Set.copyOf(pageNumbers), null, true).text();
    }

    /**
     * @param onlyPages      null: every page
     * @param routeThreshold task 0064: null routes no page
     * @param lenient        task 0064: a page OCR cannot read (no text, or too poor) fails nothing: it is kept with what
     *                       was read. Every other failure (OCR missing, timed out, ...) stops the document as before.
     */
    private RoutedOutput extract(InputStream content, String mimeType, Set<Integer> onlyPages, Double routeThreshold,
                                 boolean lenient) {
        if (!PDF_MIME_TYPE.equals(mimeType)) {
            return new RoutedOutput(onlyPages == null ? nativeExtractor.extract(content, mimeType)
                    : nativeExtractor.extractPages(content, mimeType, onlyPages), List.of());
        }

        try (PdfBoxTempFileSupport.StagedPdf staged = PdfBoxTextExtractionAdapter.stageForProcessing(content, "lexavault-hybrid-text-")) {
            PDDocument document = staged.document();
            PdfBoxTextExtractionAdapter.refuseUnreadableForm(document);
            TextExtractionOutput nativeOutput = onlyPages == null ? nativeExtractor.extractDocument(document) : null;
            PDFRenderer renderer = new PDFRenderer(document);
            List<PageText> normalizedPages = new ArrayList<>(document.getNumberOfPages());
            boolean hasEmbeddedText = false;
            boolean usedOcr = false;
            long totalChars = 0;
            PageRouter router = routeThreshold == null ? null : new PageRouter(ocrPort, properties);
            List<PageRoute> routes = new ArrayList<>();

            for (int index = 0; index < document.getNumberOfPages(); index++) {
                int pageNumber = index + 1;
                if (onlyPages != null && !onlyPages.contains(pageNumber)) continue;
                PageText nativePage = nativeOutput != null
                        ? nativePage(nativeOutput.pages(), pageNumber)
                        : nativeExtractor.extractPage(document, pageNumber);

                if (nativePage != null && !nativePage.text().isBlank()) {
                    hasEmbeddedText = true;
                }

                if (hasMeaningfulNativeText(nativePage)
                        && !shouldOcrSparseNativePage(renderer, index, nativePage)) {
                    // Task 0053: with the form fields; whether the page needs OCR was decided by its own text.
                    PageText withFields = PdfFormFieldText.with(document, document.getPage(index), nativePage);
                    normalizedPages.add(withFields);
                    totalChars += withFields.text().length();
                    if (router != null) routes.add(router.route(document, renderer, index, null, true, routeThreshold));
                    continue;
                }

                Recognized ocrPage;
                try {
                    ocrPage = recognizePage(document.getPage(index), renderer, index, pageNumber, lenient);
                } catch (TechnicalDependencyException failure) {
                    // Task 0039: the failure carries the page number (only the number) up to Support. The code,
                    // retry and outcome are unchanged.
                    throw failure.page() != null ? failure : failure.withPage(pageNumber);
                }
                PageText withFields = PdfFormFieldText.with(document, document.getPage(index), ocrPage.page());
                normalizedPages.add(withFields);
                totalChars += withFields.text().length();
                usedOcr = true;
                if (router != null) {
                    routes.add(router.route(document, renderer, index, ocrPage.output(), ocrPage.readable(),
                            routeThreshold));
                }
            }

            return new RoutedOutput(new TextExtractionOutput(normalizedPages, totalChars, hasEmbeddedText, usedOcr),
                    routes);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to normalize PDF text", e);
        }
    }

    /** @param readable false only when lenient: OCR could not read the page (no text, or too poor) */
    private record Recognized(PageText page, OcrPort.OcrPageOutput output, boolean readable) { }

    private Recognized recognizePage(PDPage page, PDFRenderer renderer,
                                     int pageIndex, int pageNumber, boolean lenient) throws IOException {
        int dpi = Math.max(72, properties.getRenderDpi());
        BufferedImage image = renderer.renderImageWithDPI(pageIndex, dpi, ImageType.RGB);
        try {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", png)) {
                throw new OcrProcessingException(TechnicalFailureCode.OCR_PROCESS_FAILED, false, "Unable to render PDF page for OCR", com.securedoc.platform.domain.error.LvErrorCode.PRC_006);
            }

            OcrPort.OcrPageOutput output = ocrPort.recognize(new OcrPort.OcrPageInput(
                    pageNumber, png.toByteArray(), "image/png", image.getWidth(), image.getHeight()));

            boolean visuallyBlank = isVisuallyBlank(image);
            boolean noText = output.text().isBlank() && !visuallyBlank;
            boolean tooPoor = !output.text().isBlank()
                    && output.confidence() < clamp01(properties.getMinimumPageConfidence());
            if (noText && !lenient) {
                throw new OcrProcessingException(
                        TechnicalFailureCode.OCR_PROCESS_FAILED, false,
                        "OCR produced no readable text for a non-blank page", com.securedoc.platform.domain.error.LvErrorCode.PRC_002);
            }
            if (tooPoor && !lenient) {
                throw new OcrProcessingException(
                        TechnicalFailureCode.OCR_PROCESS_FAILED, false,
                        "OCR confidence is below the configured minimum", com.securedoc.platform.domain.error.LvErrorCode.PRC_003);
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
            return new Recognized(new PageText(pageNumber, pageWidth, pageHeight,
                    output.text(), spans, visualRegions, ExtractionSource.OCR, output.confidence()), output,
                    !noText && !tooPoor);
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

    static double darkPixelRatio(BufferedImage image) {
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
