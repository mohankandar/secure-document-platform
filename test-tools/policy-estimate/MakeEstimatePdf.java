import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.File;

/**
 * Task 0019 test tool. Writes a one-page PDF for the policy estimate check:
 * <ul>
 *   <li>{@code plain}: text with none of the mock's fake values, so detection keeps none of the mock's answers;</li>
 *   <li>{@code blank}: a page with no text, so detection finds nothing.</li>
 * </ul>
 * Run with PDFBox on the class path:  java -cp <pdfbox jars> MakeEstimatePdf.java plain|blank out.pdf
 */
public class MakeEstimatePdf {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !(args[0].equals("plain") || args[0].equals("blank"))) {
            throw new IllegalArgumentException("usage: MakeEstimatePdf plain|blank <out.pdf>");
        }
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            if (args[0].equals("plain")) {
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.setLeading(24);
                    content.newLineAtOffset(72, 700);
                    for (String line : new String[]{"Cover letter for a test case", "Nothing sensitive is written here.", "End of page"}) {
                        content.showText(line);
                        content.newLine();
                    }
                    content.endText();
                }
            }
            document.save(new File(args[1]));
        }
    }
}
