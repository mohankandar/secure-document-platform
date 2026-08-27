package com.securedoc.platform.infrastructure.redaction;

import com.securedoc.platform.application.port.out.PdfRedactionPort;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Local/demo PDF redaction using Apache PDFBox.
 * Produces a burn-flat redacted PDF where:
 * <ul>
 *   <li>Each page is rendered to an image</li>
 *   <li>Solid black rectangles are drawn over redaction areas on the image</li>
 *   <li>A new PDF is created from the redacted page images</li>
 *   <li>Original text layer is NOT preserved (no text under redaction boxes)</li>
 *   <li>Original metadata, annotations, and attachments are NOT copied</li>
 * </ul>
 *
 * <p>This produces a safe, flattened redacted document suitable for local/demo use.
 * File size may be larger than the original due to image-based pages.
 *
 * <p>PDFBox types are NOT exposed outside this class.
 * Document text/content is NOT logged.
 */
@Component
public class PdfBoxBurnFlatRedactionAdapter implements PdfRedactionPort {

    /** Render at 150 DPI for reasonable quality vs. file size balance. */
    private static final float RENDER_DPI = 150f;

    @Override
    public RedactionOutput redact(InputStream originalPdf, List<RedactionTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            throw new IllegalArgumentException("Redaction targets must not be empty");
        }

        // Group targets by page number for efficient processing
        Map<Integer, List<RedactionTarget>> targetsByPage = targets.stream()
                .collect(Collectors.groupingBy(RedactionTarget::pageNumber));

        try (PDDocument sourceDoc = Loader.loadPDF(originalPdf.readAllBytes())) {
            int pageCount = sourceDoc.getNumberOfPages();
            PDFRenderer renderer = new PDFRenderer(sourceDoc);

            int totalRedactions = 0;

            // Create a new document from rendered/redacted images
            try (PDDocument outputDoc = new PDDocument()) {
                for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                    int pageNumber = pageIndex + 1;

                    // Render page to image
                    BufferedImage pageImage = renderer.renderImageWithDPI(pageIndex, RENDER_DPI);

                    // Apply redaction boxes if this page has targets
                    List<RedactionTarget> pageTargets = targetsByPage.get(pageNumber);
                    if (pageTargets != null && !pageTargets.isEmpty()) {
                        applyRedactionBoxes(pageImage, pageTargets);
                        totalRedactions += pageTargets.size();
                    }

                    // Add redacted image as a new page in output PDF
                    addImagePage(outputDoc, pageImage);
                }

                // Serialize output PDF to bytes
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                outputDoc.save(outputStream);

                return new RedactionOutput(outputStream.toByteArray(), pageCount, totalRedactions);
            }

        } catch (IOException e) {
            throw new UncheckedIOException("Failed to perform PDF redaction", e);
        }
    }

    /**
     * Draws solid black rectangles on the rendered page image at the specified normalized coordinates.
     */
    private void applyRedactionBoxes(BufferedImage image, List<RedactionTarget> targets) {
        int imgWidth = image.getWidth();
        int imgHeight = image.getHeight();

        Graphics2D g2d = image.createGraphics();
        try {
            g2d.setColor(Color.BLACK);

            for (RedactionTarget target : targets) {
                // Convert normalized coordinates (0.0-1.0) to pixel coordinates
                int x = (int) Math.round(target.left() * imgWidth);
                int y = (int) Math.round(target.top() * imgHeight);
                int w = (int) Math.round(target.width() * imgWidth);
                int h = (int) Math.round(target.height() * imgHeight);

                // Ensure minimum 1px size
                w = Math.max(w, 1);
                h = Math.max(h, 1);

                // Clamp to image bounds
                x = Math.min(x, imgWidth - 1);
                y = Math.min(y, imgHeight - 1);
                w = Math.min(w, imgWidth - x);
                h = Math.min(h, imgHeight - y);

                g2d.fillRect(x, y, w, h);
            }
        } finally {
            g2d.dispose();
        }
    }

    /**
     * Adds a BufferedImage as a full-page image in the output PDF document.
     */
    private void addImagePage(PDDocument doc, BufferedImage image) throws IOException {
        // Create page with dimensions matching the image at render DPI
        float pageWidth = image.getWidth() * 72f / RENDER_DPI;
        float pageHeight = image.getHeight() * 72f / RENDER_DPI;

        PDPage page = new PDPage(new PDRectangle(pageWidth, pageHeight));
        doc.addPage(page);

        PDImageXObject pdImage = LosslessFactory.createFromImage(doc, image);

        try (PDPageContentStream contentStream = new PDPageContentStream(doc, page)) {
            contentStream.drawImage(pdImage, 0, 0, pageWidth, pageHeight);
        }
    }
}
