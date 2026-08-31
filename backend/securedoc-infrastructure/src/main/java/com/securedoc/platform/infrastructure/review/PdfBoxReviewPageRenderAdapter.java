package com.securedoc.platform.infrastructure.review;

import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.ReviewPageRenderPort;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * PDFBox implementation that renders one source page to PNG for the reviewer workbench.
 * It never returns the full original document and never logs document content.
 */
@Component
public class PdfBoxReviewPageRenderAdapter implements ReviewPageRenderPort {

    private static final float PREVIEW_DPI = 144f;

    @Override
    public RenderedPage render(InputStream pdfContent, int pageNumber) {
        if (pageNumber < 1) {
            throw new ValidationException("Review page number must be at least 1");
        }
        try (PDDocument document = Loader.loadPDF(pdfContent.readAllBytes())) {
            if (pageNumber > document.getNumberOfPages()) {
                throw new ValidationException("Review page is outside the source document");
            }
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage image = renderer.renderImageWithDPI(pageNumber - 1, PREVIEW_DPI, ImageType.RGB);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) {
                throw new IllegalStateException("PNG writer is unavailable");
            }
            return new RenderedPage(output.toByteArray(), "image/png", image.getWidth(), image.getHeight());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render review page preview", e);
        }
    }
}
