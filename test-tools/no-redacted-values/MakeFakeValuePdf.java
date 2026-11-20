import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.File;

/**
 * Task 0015 test tool. Writes a one-page text PDF holding fake values the detectors accept.
 * Run with PDFBox on the class path:  java -cp <pdfbox jars> MakeFakeValuePdf.java out.pdf
 * The values are test values only (no real person).
 */
public class MakeFakeValuePdf {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: MakeFakeValuePdf <out.pdf>");
        }
        String[] lines = {
                "Loan application for a test borrower",
                "Borrower SSN: 412-55-7531",
                "Card Number: 4000 0566 5566 5556",
                "Date of Birth: 03/14/1962",
                "End of page"
        };
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.setLeading(24);
                content.newLineAtOffset(72, 700);
                for (String line : lines) {
                    content.showText(line);
                    content.newLine();
                }
                content.endText();
            }
            document.save(new File(args[0]));
        }
    }
}
