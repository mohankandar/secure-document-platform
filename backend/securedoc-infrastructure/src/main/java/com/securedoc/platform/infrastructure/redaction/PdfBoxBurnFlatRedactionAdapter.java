package com.securedoc.platform.infrastructure.redaction;

import com.securedoc.platform.application.port.out.PdfRedactionPort;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;
import com.securedoc.platform.infrastructure.pdf.PdfBoxTempFileSupport;
import com.securedoc.platform.infrastructure.pdf.TempFileReplayableContent;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
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
 *   <li>Pages with interactive annotations are also rendered so visible annotation/form
 *       appearance is preserved without copying active annotation objects.</li>
 *   <li>Static untouched pages are imported losslessly so their native text/vector content,
 *       searchability, and compact representation are preserved.</li>
 *   <li>A new document is always created, so source document metadata/catalog attachments are
 *       not copied. Imported static pages are stripped of page-level metadata/actions.</li>
 *   <li>Original text is never preserved on a page that contains a redaction target.</li>
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

    @Override
    public RedactionOutput redact(InputStream originalPdf, List<RedactionTarget> targets) {
        List<RedactionTarget> safeTargets = targets == null ? List.of() : List.copyOf(targets);
        Map<Integer, List<RedactionTarget>> targetsByPage = safeTargets.stream()
                .collect(Collectors.groupingBy(RedactionTarget::pageNumber));
        Path outputPath = null;

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

                if (!pageTargets.isEmpty() || hasAnnotations(sourcePage)) {
                    BufferedImage pageImage = renderer.renderImageWithDPI(pageIndex, RENDER_DPI);
                    try {
                        if (!pageTargets.isEmpty()) {
                            applyRedactionBoxes(pageImage, pageTargets);
                            totalRedactions += pageTargets.size();
                        }
                        addImagePage(outputDoc, pageImage);
                    } finally {
                        pageImage.flush();
                    }
                } else {
                    importStaticPage(outputDoc, sourcePage);
                }
            }

            outputPath = PdfBoxTempFileSupport.newOutputPath("lexavault-redaction-output-");
            outputDoc.save(outputPath.toFile());
            TempFileReplayableContent content = TempFileReplayableContent.owned(outputPath);
            outputPath = null;
            return new RedactionOutput(content, pageCount, totalRedactions);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to perform PDF redaction", e);
        } finally {
            if (outputPath != null) {
                try { Files.deleteIfExists(outputPath); } catch (IOException ignored) { }
            }
        }
    }

    private boolean hasAnnotations(PDPage page) throws IOException {
        return !page.getAnnotations().isEmpty();
    }

    /**
     * Imports an untouched static page without rasterizing it. Effective resources are explicitly
     * retained because PDFBox importPage intentionally ignores inherited page-tree resources.
     * Page-level active/auxiliary objects are removed so the output remains a controlled artifact.
     */
    private void importStaticPage(PDDocument outputDoc, PDPage sourcePage) throws IOException {
        PDPage importedPage = outputDoc.importPage(sourcePage);

        PDResources effectiveResources = sourcePage.getResources();
        if (effectiveResources != null) {
            importedPage.setResources(effectiveResources);
        }

        COSDictionary pageDictionary = importedPage.getCOSObject();
        pageDictionary.removeItem(COSName.ANNOTS);
        pageDictionary.removeItem(COSName.AA);
        pageDictionary.removeItem(COSName.METADATA);
        pageDictionary.removeItem(COSName.getPDFName("PieceInfo"));
        pageDictionary.removeItem(COSName.getPDFName("Thumb"));
        pageDictionary.removeItem(COSName.getPDFName("StructParents"));
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
        } finally {
            g2d.dispose();
        }
    }

    /** Adds a rendered page image as a new PDF page. */
    private void addImagePage(PDDocument doc, BufferedImage image) throws IOException {
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
