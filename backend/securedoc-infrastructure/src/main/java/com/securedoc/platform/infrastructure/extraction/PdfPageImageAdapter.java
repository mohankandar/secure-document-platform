package com.securedoc.platform.infrastructure.extraction;

import com.securedoc.platform.application.port.out.PageImagePort;
import com.securedoc.platform.infrastructure.pdf.PdfBoxTempFileSupport;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Task 0064: one page as an image, in memory only (the same rendering the redaction draws its boxes on). */
@Component
public class PdfPageImageAdapter implements PageImagePort {

    /** About 200 dpi of a letter page: what the line's shape needs. */
    private static final int CHECK_LINE_LONG_SIDE = 2200;

    @Override
    public List<CheckLine> checkLines(InputStream pdf, int page) throws IOException {
        try (PdfBoxTempFileSupport.StagedPdf staged =
                     PdfBoxTextExtractionAdapter.stageForProcessing(pdf, "lexavault-check-line-")) {
            PDDocument document = staged.document();
            PDRectangle box = document.getPage(page - 1).getCropBox();
            float longPoints = Math.max(1f, Math.max(box.getWidth(), box.getHeight()));
            BufferedImage image = new PDFRenderer(document)
                    .renderImageWithDPI(page - 1, CHECK_LINE_LONG_SIDE * 72f / longPoints, ImageType.GRAY);
            try {
                return CheckLineFinder.find(image).stream().map(l -> new CheckLine(l.routing(), l.account())).toList();
            } finally {
                image.flush();
            }
        }
    }

    @Override
    public PageImage render(InputStream pdf, int page, int longSidePixels) throws IOException {
        try (PdfBoxTempFileSupport.StagedPdf staged =
                     PdfBoxTextExtractionAdapter.stageForProcessing(pdf, "lexavault-vision-page-")) {
            PDDocument document = staged.document();
            PDRectangle box = document.getPage(page - 1).getCropBox();
            float longPoints = Math.max(1f, Math.max(box.getWidth(), box.getHeight()));
            BufferedImage image = new PDFRenderer(document)
                    .renderImageWithDPI(page - 1, longSidePixels * 72f / longPoints, ImageType.RGB);
            try {
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                if (!ImageIO.write(image, "png", png)) throw new IOException("The page image could not be written");
                return new PageImage(png.toByteArray(), image.getWidth(), image.getHeight());
            } finally {
                image.flush();
            }
        }
    }
}
