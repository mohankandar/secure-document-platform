package com.securedoc.platform.api.review;

import com.securedoc.platform.domain.finding.FindingLocation;
import com.securedoc.platform.infrastructure.review.PdfBoxReviewPageRenderAdapter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused regression check: Manual Review page numbers are one-based and render the requested PDF page. */
class ReviewPageRenderExactPageTest {

    @Test
    void rendersTheExactRequestedOneBasedPage() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage(new PDRectangle(200, 300)));
            document.addPage(new PDPage(new PDRectangle(400, 200)));
            document.save(output);
            pdf = output.toByteArray();
        }

        var adapter = new PdfBoxReviewPageRenderAdapter();

        var first = adapter.render(new ByteArrayInputStream(pdf), 1);
        assertEquals(400, first.pixelWidth());
        assertEquals(600, first.pixelHeight());

        var second = adapter.render(new ByteArrayInputStream(pdf), 2);
        assertEquals(800, second.pixelWidth());
        assertEquals(400, second.pixelHeight());
        assertEquals("image/png", second.mimeType());
    }

    @Test
    void appliesConfirmedFindingMasksOnlyToTheRenderedPreview() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage(new PDRectangle(200, 200)));
            document.save(output);
            pdf = output.toByteArray();
        }

        var adapter = new PdfBoxReviewPageRenderAdapter();
        var mask = FindingLocation.BoundingBox.of(0.25, 0.25, 0.25, 0.25);
        var rendered = adapter.render(new ByteArrayInputStream(pdf), 1, List.of(mask));
        var image = ImageIO.read(new ByteArrayInputStream(rendered.content()));

        int maskedRgb = image.getRGB(image.getWidth() * 3 / 8, image.getHeight() * 3 / 8) & 0x00FFFFFF;
        int unmaskedRgb = image.getRGB(image.getWidth() * 7 / 8, image.getHeight() * 7 / 8) & 0x00FFFFFF;
        assertEquals(0x000000, maskedRgb);
        assertTrue(unmaskedRgb != 0x000000);
    }
}
