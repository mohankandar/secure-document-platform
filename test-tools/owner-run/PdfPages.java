import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Task 0106 test tool. Reads one PDF and writes, into the output folder, page-N.png (rendered at 150 dpi) and
 * pages.json: per page the text layer (text drawn on the page, form-field values and annotation text, all of it,
 * also text too small or too pale to see) and whether the page has any text at all.
 * Run with PDFBox on the class path:  java -cp <pdfbox jars> PdfPages.java in.pdf out-folder
 * A file that cannot be opened writes pages.json with "error" and no pages.
 */
public class PdfPages {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("usage: PdfPages <in.pdf> <out-folder>");
        File out = new File(args[1]);
        out.mkdirs();
        StringBuilder json = new StringBuilder("{\"pages\":[");
        try (PDDocument document = Loader.loadPDF(new File(args[0]))) {
            PDFTextStripper stripper = new PDFTextStripper();
            PDFRenderer renderer = new PDFRenderer(document);
            PDAcroForm form = document.getDocumentCatalog().getAcroForm();
            int count = document.getNumberOfPages();
            for (int i = 0; i < count; i++) {
                PDPage page = document.getPage(i);
                final int index = i;
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                String drawn = stripper.getText(document);
                StringBuilder extra = new StringBuilder();
                for (PDAnnotation a : page.getAnnotations()) {
                    if (a.getContents() != null) extra.append('\n').append(a.getContents());
                }
                if (form != null) {
                    for (PDField f : form.getFieldTree()) {
                        boolean onPage = f.getWidgets().stream().anyMatch(w -> w.getPage() == null ? index == 0 : w.getPage().equals(page));
                        if (onPage && f.getValueAsString() != null && !f.getValueAsString().isEmpty()) {
                            extra.append('\n').append(f.getValueAsString());
                        }
                    }
                }
                ImageIO.write(renderer.renderImageWithDPI(i, 150, ImageType.RGB), "png", new File(out, "page-" + (i + 1) + ".png"));
                String text = drawn + extra;
                json.append(i == 0 ? "" : ",").append("{\"page\":").append(i + 1)
                        .append(",\"hasText\":").append(!drawn.isBlank())
                        .append(",\"text\":\"").append(escape(text)).append("\"}");
            }
            json.append("]}");
        } catch (Exception e) {
            json = new StringBuilder("{\"pages\":[],\"error\":\"" + escape(e.getClass().getSimpleName()) + "\"}");
        }
        Files.writeString(new File(out, "pages.json").toPath(), json, StandardCharsets.UTF_8);
    }

    private static String escape(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> { }
                case '\t' -> b.append(' ');
                default -> {
                    if (c < 0x20) b.append(' ');
                    else b.append(c);
                }
            }
        }
        return b.toString();
    }
}
