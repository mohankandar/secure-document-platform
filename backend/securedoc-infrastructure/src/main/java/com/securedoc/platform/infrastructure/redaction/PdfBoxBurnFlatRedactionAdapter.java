package com.securedoc.platform.infrastructure.redaction;

import com.securedoc.platform.application.port.out.OcrPort;
import com.securedoc.platform.application.port.out.PdfRedactionPort;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.util.Matrix;
import org.springframework.beans.factory.annotation.Autowired;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;
import com.securedoc.platform.infrastructure.pdf.PdfBoxTempFileSupport;
import com.securedoc.platform.infrastructure.pdf.TempFileCleanup;
import com.securedoc.platform.infrastructure.pdf.TempFileReplayableContent;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;
import javax.imageio.ImageIO;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * PDFBox V1 permanent-redaction adapter.
 *
 * <p>The class name is retained for compatibility, but the implementation is intentionally
 * selective rather than flattening the entire document:
 * <ul>
 *   <li>Pages that contain redaction targets are rendered and burn-flattened.</li>
 *   <li>Untouched pages are imported losslessly so their native text/vector content,
 *       searchability, and compact representation are preserved. Task 0050: their annotations and form
 *       fields become part of the page as they look, and nothing hidden is copied ({@link PdfOutputCleaner}).
 *       A page that can show a hidden layer is drawn as an image instead.</li>
 *   <li>A new document is always created, so source document metadata, attachments and earlier
 *       saves are not copied.</li>
 *   <li>Original text is never preserved on a page that contains a redaction target.</li>
 *   <li>Task 0044 (option B, searchable on): a page with redaction targets also gets an invisible text layer made by
 *       OCR of its already-redacted image, so the page is searchable and a covered value cannot reach the text.
 *       Off is exactly the output above.</li>
 * </ul>
 *
 * <p>This keeps irreversible redaction semantics while avoiding the large file-size and
 * searchability penalty of rasterizing every page in a mostly-unredacted document.
 *
 * <p>PDFBox types are not exposed outside this class. Document text/content is not logged.
 */
@Component
public class PdfBoxBurnFlatRedactionAdapter implements PdfRedactionPort {

    /** Render redacted/dynamic pages at 150 DPI for current V1 visual quality. */
    private static final float RENDER_DPI = 150f;
    /**
     * Task 0044: the redacted page image is enlarged this much (no new detail, only the same pixels interpolated)
     * before OCR, because Tesseract reads 150 DPI text poorly. The page itself stays the 150 DPI image.
     */
    private static final int OCR_UPSCALE = 2;

    /** The dates of the output (task 0050). Fixed by tests that compare two outputs byte for byte. */
    java.time.Clock clock = java.time.Clock.systemUTC();

    /** Null when built without OCR (tests of today's output); a searchable request then refuses. */
    private final OcrPort ocr;

    /** Today's engine without OCR: image-only redacted pages. */
    public PdfBoxBurnFlatRedactionAdapter() {
        this.ocr = null;
    }

    @Autowired
    public PdfBoxBurnFlatRedactionAdapter(OcrPort ocr) {
        this.ocr = Objects.requireNonNull(ocr);
    }

    @Override
    public RedactionOutput redact(InputStream originalPdf, List<RedactionTarget> targets) {
        return redact(originalPdf, targets, RedactionOptions.IMAGE_ONLY);
    }

    @Override
    public RedactionOutput redact(InputStream originalPdf, List<RedactionTarget> targets, RedactionOptions options) {
        boolean searchable = options != null && options.searchableText();
        if (searchable && ocr == null) {
            // Fail closed: never return image-only pages when a text layer was asked for.
            throw new RedactionEngineException("Searchable redacted pages need OCR, which is not available",
                    new IOException("OCR not available"));
        }
        List<RedactionTarget> safeTargets = targets == null ? List.of() : List.copyOf(targets);
        Map<Integer, List<RedactionTarget>> targetsByPage = safeTargets.stream()
                .collect(Collectors.groupingBy(RedactionTarget::pageNumber));
        Path outputPath = null;
        TempFileReplayableContent content = null;

        try (PdfBoxTempFileSupport.StagedPdf staged = PdfBoxTempFileSupport.stage(originalPdf, "lexavault-redaction-source-");
             PDDocument outputDoc = PdfBoxTempFileSupport.newDiskBackedDocument()) {
            PDDocument sourceDoc = staged.document();
            int pageCount = sourceDoc.getNumberOfPages();
            PDFRenderer renderer = new PDFRenderer(sourceDoc);
            int totalRedactions = 0;

            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                int pageNumber = pageIndex + 1;
                PDPage sourcePage = sourceDoc.getPage(pageIndex);
                List<RedactionTarget> pageTargets = targetsByPage.getOrDefault(pageNumber, List.of());

                if (!pageTargets.isEmpty() || mustBeDrawn(sourceDoc, sourcePage)) {
                    BufferedImage pageImage = renderer.renderImageWithDPI(pageIndex, RENDER_DPI);
                    try {
                        if (!pageTargets.isEmpty()) {
                            applyRedactionBoxes(pageImage, pageTargets);
                            totalRedactions += pageTargets.size();
                        }
                        PDPage outputPage = addImagePage(outputDoc, pageImage);
                        if (searchable) {
                            // Option B: OCR of the already-redacted image, so a covered value cannot reach the text.
                            addInvisibleText(outputDoc, outputPage, recognize(pageNumber, pageImage));
                        }
                    } finally {
                        pageImage.flush();
                    }
                } else {
                    PdfOutputCleaner.copyPage(sourceDoc, outputDoc, sourcePage);
                }
            }

            boolean signed = !sourceDoc.getSignatureDictionaries().isEmpty();
            PdfOutputCleaner.clean(outputDoc, clock.instant());
            outputPath = PdfBoxTempFileSupport.newOutputPath("lexavault-redaction-output-");
            outputDoc.save(outputPath.toFile());
            content = TempFileReplayableContent.owned(outputPath);
            outputPath = null;
            return new RedactionOutput(content, pageCount, totalRedactions, signed);
        } catch (IOException e) {
            // Task 0023 B9 (#373): also reached when closing the staged source fails after the
            // output was created; the caller never receives it, so remove it here.
            if (content != null) content.close();
            throw new RedactionEngineException("Failed to perform PDF redaction", e);
        } catch (RuntimeException e) {
            if (content != null) content.close();
            throw e;
        } finally {
            // Task 0023 B9 (#373): the redacted temp output is removed on every failure path.
            // A failed delete is logged with a temp id, never a name or path.
            if (outputPath != null) {
                TempFileCleanup.deleteQuietly(outputPath, "REDACTION_OUTPUT");
            }
        }
    }

    /** A page that could show a hidden layer, or whose content cannot be read, is never copied. */
    private static boolean mustBeDrawn(PDDocument source, PDPage page) {
        try {
            if (PdfOutputCleaner.hasHiddenLayer(source, page)) return true;
            try (var content = page.getContents()) {
                if (content != null) new org.apache.pdfbox.pdfparser.PDFStreamParser(content.readAllBytes()).parse();
            }
            return false;
        } catch (IOException | RuntimeException unreadable) {
            return true;
        }
    }

    /** Draws solid black rectangles on the rendered page image at normalized coordinates. */
    private void applyRedactionBoxes(BufferedImage image, List<RedactionTarget> targets) {
        int imgWidth = image.getWidth();
        int imgHeight = image.getHeight();

        Graphics2D g2d = image.createGraphics();
        try {
            g2d.setColor(Color.BLACK);

            for (RedactionTarget target : targets) {
                int x = (int) Math.round(target.left() * imgWidth);
                int y = (int) Math.round(target.top() * imgHeight);
                int w = (int) Math.round(target.width() * imgWidth);
                int h = (int) Math.round(target.height() * imgHeight);

                w = Math.max(w, 1);
                h = Math.max(h, 1);

                x = Math.max(0, Math.min(x, imgWidth - 1));
                y = Math.max(0, Math.min(y, imgHeight - 1));
                w = Math.min(w, imgWidth - x);
                h = Math.min(h, imgHeight - y);

                g2d.fillRect(x, y, w, h);
            }
            // Task 0062: after every cover is drawn, so nothing of the original is under the digits.
            for (RedactionTarget target : targets) {
                if (target.kept() != null) drawKeptDigits(g2d, target, imgWidth, imgHeight);
            }
        } finally {
            g2d.dispose();
        }
    }

    private static final String[] DIGIT_GLYPHS = {"zero", "one", "two", "three", "four", "five", "six", "seven",
            "eight", "nine"};
    /** Smaller digits are not readable at the page's resolution; the cover then stays plain. */
    private static final double SMALLEST_DIGIT_PIXELS = 7;

    /** Draws the kept digits in white on the end of the cover. The shapes come from PDFBox's own font. */
    private static void drawKeptDigits(Graphics2D g2d, RedactionTarget target, int imgWidth, int imgHeight) {
        RedactionTarget area = target.keptPart();
        double areaWidth = area.width() * imgWidth;
        double areaHeight = area.height() * imgHeight;
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        try {
            float[] em = font.getFontMatrix().getScaleX() == 0 ? null
                    : new float[]{font.getFontMatrix().getScaleX(), font.getFontMatrix().getScaleY()};
            if (em == null) return;
            String digits = target.kept().digitsToDraw();
            double capHeight = 0.0;
            double width = 0.0; // of one em
            for (char c : digits.toCharArray()) {
                capHeight = Math.max(capHeight, font.getPath(DIGIT_GLYPHS[c - '0']).getBounds2D().getMaxY() * em[1]);
                width += font.getStringWidth(String.valueOf(c)) / 1000.0;
            }
            if (capHeight <= 0 || width <= 0) return;
            double size = Math.min(areaHeight * 0.70 / capHeight, areaWidth * 0.92 / width);
            if (size * capHeight < SMALLEST_DIGIT_PIXELS) return;
            double x = area.left() * imgWidth + (areaWidth - size * width) / 2;
            double baseline = area.top() * imgHeight + (areaHeight + size * capHeight) / 2;
            Object before = g2d.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setColor(Color.WHITE);
            for (char c : digits.toCharArray()) {
                java.awt.geom.AffineTransform place = java.awt.geom.AffineTransform.getTranslateInstance(x, baseline);
                place.scale(size * em[0], -size * em[1]);
                g2d.fill(place.createTransformedShape(font.getPath(DIGIT_GLYPHS[c - '0'])));
                x += size * font.getStringWidth(String.valueOf(c)) / 1000.0;
            }
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, before);
            g2d.setColor(Color.BLACK);
        } catch (IOException e) {
            // The cover stays plain: the value is removed in full.
        }
    }

    /** Adds a rendered page image as a new PDF page. */
    private PDPage addImagePage(PDDocument doc, BufferedImage image) throws IOException {
        float pageWidth = image.getWidth() * 72f / RENDER_DPI;
        float pageHeight = image.getHeight() * 72f / RENDER_DPI;

        PDPage page = new PDPage(new PDRectangle(pageWidth, pageHeight));
        doc.addPage(page);

        PDImageXObject pdImage = LosslessFactory.createFromImage(doc, image);
        try (PDPageContentStream contentStream = new PDPageContentStream(doc, page)) {
            contentStream.drawImage(pdImage, 0, 0, pageWidth, pageHeight);
        }
        return page;
    }

    /** OCR of the redacted page image (enlarged, see {@link #OCR_UPSCALE}). The text stays in memory only. */
    private OcrPort.OcrPageOutput recognize(int pageNumber, BufferedImage redactedImage) throws IOException {
        int w = redactedImage.getWidth() * OCR_UPSCALE;
        int h = redactedImage.getHeight() * OCR_UPSCALE;
        BufferedImage enlarged = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = enlarged.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(redactedImage, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream png = new ByteArrayOutputStream()) {
            ImageIO.write(enlarged, "png", png);
            return ocr.recognize(new OcrPort.OcrPageInput(pageNumber, png.toByteArray(), "image/png", w, h));
        } finally {
            enlarged.flush();
        }
    }

    /**
     * Adds the OCR words as invisible text (rendering mode "neither") over their place on the image: the standard
     * "image plus hidden text" page, so the page can be searched and copied. Each word is sized to its OCR box.
     * Characters the standard font cannot encode are left out; a word with none left is skipped.
     */
    private void addInvisibleText(PDDocument doc, PDPage page, OcrPort.OcrPageOutput ocrOutput) throws IOException {
        if (ocrOutput.spans().isEmpty()) return;
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        float pageWidth = page.getMediaBox().getWidth();
        float pageHeight = page.getMediaBox().getHeight();
        String text = ocrOutput.text();
        try (PDPageContentStream cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
            cs.beginText();
            cs.setRenderingMode(RenderingMode.NEITHER);
            for (OcrPort.OcrTextSpan span : ocrOutput.spans()) {
                if (span.endOffset() > text.length() || span.endOffset() <= span.startOffset()) continue;
                String word = encodable(font, text.substring(span.startOffset(), span.endOffset()).strip());
                if (word.isEmpty()) continue;
                float boxWidth = (float) (span.width() * pageWidth);
                float boxHeight = (float) (span.height() * pageHeight);
                if (boxWidth <= 0 || boxHeight <= 0) continue;
                float fontSize = Math.max(1f, boxHeight);
                float natural = font.getStringWidth(word) / 1000f * fontSize;
                if (natural <= 0) continue;
                float x = (float) (span.left() * pageWidth);
                // Baseline a fifth of the box above its bottom, room for descenders.
                float y = (float) (pageHeight - (span.top() + span.height()) * pageHeight) + boxHeight * 0.2f;
                cs.setFont(font, fontSize);
                cs.setHorizontalScaling(100f * boxWidth / natural);
                cs.setTextMatrix(Matrix.getTranslateInstance(x, y));
                cs.showText(word);
            }
            cs.endText();
        }
    }

    private static String encodable(PDType1Font font, String word) {
        StringBuilder out = new StringBuilder(word.length());
        for (int i = 0; i < word.length(); i++) {
            String c = String.valueOf(word.charAt(i));
            try {
                font.encode(c);
                out.append(c);
            } catch (IllegalArgumentException | IOException e) {
                // Not in the font's encoding: left out of the hidden text.
            }
        }
        return out.toString();
    }
}
