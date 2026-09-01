package com.securedoc.platform.api.review;

import com.securedoc.platform.infrastructure.review.PdfBoxReviewPageRenderAdapter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
