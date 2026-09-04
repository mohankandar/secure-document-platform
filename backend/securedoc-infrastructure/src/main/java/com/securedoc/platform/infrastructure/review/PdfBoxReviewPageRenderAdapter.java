package com.securedoc.platform.infrastructure.review;

import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.ReviewPageRenderPort;
import com.securedoc.platform.domain.finding.FindingLocation;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * PDFBox implementation that renders one source page to PNG for the reviewer workbench.
 * It never returns the full original document and never logs document content.
 * Confirmed-finding masks are applied only to the in-memory preview image; the source PDF is unchanged.
 */
@Component
public class PdfBoxReviewPageRenderAdapter implements ReviewPageRenderPort {

    private static final Logger log = LoggerFactory.getLogger(PdfBoxReviewPageRenderAdapter.class);
    private static final float PREVIEW_DPI = 144f;
    private static final int MASK_PADDING_PIXELS = 2;

    @Override
    public RenderedPage render(InputStream pdfContent, int pageNumber) {
        return render(pdfContent, pageNumber, List.of());
    }

    @Override
    public RenderedPage render(InputStream pdfContent, int pageNumber,
                               List<FindingLocation.BoundingBox> previewMasks) {
        if (pageNumber < 1) {
            throw new ValidationException("Review page number must be at least 1");
        }
        List<FindingLocation.BoundingBox> safeMasks = previewMasks == null ? List.of() : List.copyOf(previewMasks);
        try (PDDocument document = Loader.loadPDF(pdfContent.readAllBytes())) {
            if (pageNumber > document.getNumberOfPages()) {
                throw new ValidationException("Review page is outside the source document");
            }
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage image = renderer.renderImageWithDPI(pageNumber - 1, PREVIEW_DPI, ImageType.RGB);
            applyPreviewMasks(image, safeMasks);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) {
                throw new IllegalStateException("PNG writer is unavailable");
            }
            if (!safeMasks.isEmpty()) {
                log.info("REVIEW_PREVIEW_MASK_APPLIED page={} masks={} width={} height={}",
                        pageNumber, safeMasks.size(), image.getWidth(), image.getHeight());
            }
            return new RenderedPage(output.toByteArray(), "image/png", image.getWidth(), image.getHeight());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render review page preview", e);
        }
    }

    private void applyPreviewMasks(BufferedImage image, List<FindingLocation.BoundingBox> previewMasks) {
        if (previewMasks.isEmpty()) {
            return;
        }
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.BLACK);
            for (FindingLocation.BoundingBox mask : previewMasks) {
                int left = Math.max(0,
                        (int) Math.floor(mask.left() * image.getWidth()) - MASK_PADDING_PIXELS);
                int top = Math.max(0,
                        (int) Math.floor(mask.top() * image.getHeight()) - MASK_PADDING_PIXELS);
                int right = Math.min(image.getWidth(),
                        (int) Math.ceil((mask.left() + mask.width()) * image.getWidth()) + MASK_PADDING_PIXELS);
                int bottom = Math.min(image.getHeight(),
                        (int) Math.ceil((mask.top() + mask.height()) * image.getHeight()) + MASK_PADDING_PIXELS);
                graphics.fillRect(left, top, Math.max(1, right - left), Math.max(1, bottom - top));
            }
        } finally {
            graphics.dispose();
        }
    }
}
