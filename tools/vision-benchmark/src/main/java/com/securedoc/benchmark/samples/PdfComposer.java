package com.securedoc.benchmark.samples;

import com.securedoc.benchmark.truth.Truth;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Writes page specs to a PDF and returns the matching answer file (types and boxes, no values). */
final class PdfComposer {

    private static final int RASTER_DPI = 200;

    private PdfComposer() {
    }

    static Truth write(List<PageSpec> pages, Path pdf, long seed) throws IOException {
        Random random = new Random(seed);
        List<Truth.PageTruth> truthPages = new ArrayList<>();
        PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        try (PDDocument document = new PDDocument()) {
            int number = 0;
            for (PageSpec spec : pages) {
                number++;
                List<Truth.Item> items = new ArrayList<>();
                PageSpec.Sink sink = (type, box, handwritten) -> items.add(new Truth.Item(type, normalize(box),
                        handwritten ? Boolean.TRUE : null));
                PDPage page = new PDPage(PDRectangle.LETTER);
                document.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                    if (spec.mode == PageSpec.Mode.DIGITAL) {
                        writeDigital(document, cs, spec, sink, regular, bold);
                    } else {
                        writeScanned(document, cs, spec, sink, random, number, pages.size(), regular);
                    }
                }
                truthPages.add(new Truth.PageTruth(number, spec.kind, spec.expectedRoute, items));
            }
            document.save(pdf.toFile());
        }
        return new Truth(1, truthPages);
    }

    private static void writeDigital(PDDocument document, PDPageContentStream cs, PageSpec spec, PageSpec.Sink sink,
                                     PDType1Font regular, PDType1Font bold) throws IOException {
        for (PageSpec.El el : spec.elements) {
            switch (el) {
                case PageSpec.Text t -> {
                    if (t.text().isEmpty()) break;
                    cs.beginText();
                    cs.setFont(t.bold() ? bold : regular, (float) t.size());
                    cs.newLineAtOffset((float) t.x(), (float) (PageSpec.HEIGHT - t.y()));
                    cs.showText(t.text());
                    cs.endText();
                    if (t.truthType() != null) {
                        sink.item(t.truthType(), PageSpec.typedBox(t), false);
                    }
                }
                case PageSpec.Line l -> {
                    cs.setStrokingColor(Color.BLACK);
                    cs.setLineWidth(0.7f);
                    cs.moveTo((float) l.x1(), (float) (PageSpec.HEIGHT - l.y1()));
                    cs.lineTo((float) l.x2(), (float) (PageSpec.HEIGHT - l.y2()));
                    cs.stroke();
                }
                default -> {
                    Rectangle2D frame = PageSpec.frame(el);
                    double scale = RASTER_DPI / 72.0;
                    BufferedImage image = new BufferedImage((int) Math.ceil(frame.getWidth() * scale),
                            (int) Math.ceil(frame.getHeight() * scale), BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = image.createGraphics();
                    PageSpec.prepare(g);
                    g.scale(scale, scale);
                    g.translate(-frame.getX(), -frame.getY());
                    PageSpec.draw(el, g, sink);
                    g.dispose();
                    PDImageXObject xobject = LosslessFactory.createFromImage(document, image);
                    cs.drawImage(xobject, (float) frame.getX(), (float) (PageSpec.HEIGHT - frame.getY() - frame.getHeight()),
                            (float) frame.getWidth(), (float) frame.getHeight());
                }
            }
        }
    }

    private static void writeScanned(PDDocument document, PDPageContentStream cs, PageSpec spec, PageSpec.Sink sink,
                                     Random random, int number, int count, PDType1Font regular) throws IOException {
        int dpi = spec.mode == PageSpec.Mode.SCAN_POOR ? 150 : RASTER_DPI;
        double scale = dpi / 72.0;
        BufferedImage image = new BufferedImage((int) (PageSpec.WIDTH * scale), (int) (PageSpec.HEIGHT * scale), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        PageSpec.prepare(g);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.scale(scale, scale);
        for (PageSpec.El el : spec.elements) {
            PageSpec.draw(el, g, sink);
        }
        g.dispose();
        BufferedImage finished = switch (spec.mode) {
            case SCAN_CLEAN -> Degrade.cleanScan(image, random);
            case SCAN_POOR -> Degrade.poorScan(image, random);
            case FAX -> Degrade.fax(image, random, "FAX  FROM (555) 555-0100   SAMPLE TITLE CO   P." + number + "/" + count);
            default -> throw new IllegalStateException();
        };
        PDImageXObject xobject = spec.mode == PageSpec.Mode.FAX
                ? LosslessFactory.createFromImage(document, finished)
                : JPEGFactory.createFromImage(document, finished, spec.mode == PageSpec.Mode.SCAN_POOR ? 0.5f : 0.85f);
        cs.drawImage(xobject, 0, 0, (float) PageSpec.WIDTH, (float) PageSpec.HEIGHT);
        if (spec.hiddenTextLayer != null) {
            // An invisible OCR-style text layer that does not match the picture.
            for (PageSpec.Text t : spec.hiddenTextLayer) {
                cs.beginText();
                cs.setRenderingMode(RenderingMode.NEITHER);
                cs.setFont(regular, (float) t.size());
                cs.newLineAtOffset((float) t.x(), (float) (PageSpec.HEIGHT - t.y()));
                cs.showText(t.text());
                cs.endText();
            }
        }
    }

    static double[] normalize(Rectangle2D box) {
        double left = clamp(box.getX() / PageSpec.WIDTH);
        double top = clamp(box.getY() / PageSpec.HEIGHT);
        double width = Math.min(1 - left, box.getWidth() / PageSpec.WIDTH);
        double height = Math.min(1 - top, box.getHeight() / PageSpec.HEIGHT);
        return new double[]{round(left), round(top), round(width), round(height)};
    }

    private static double round(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
