import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Task 0048 test tool. Writes a case document for the two-server check: page 1 holds the same fake test values as the
 * task 0015 check (no real person), the other pages ordinary text. "typed" pages carry PDF text; "scanned" pages are
 * a picture of the text only, so reading them needs OCR (a Text job that takes a few seconds).
 *
 * Run with PDFBox on the class path:  java -cp <pdfbox jars> MakeCasePdf.java out.pdf typed|scanned|marked <pages>
 */
public class MakeCasePdf {
    private static final String[] VALUES = {
            "Loan application for a test borrower",
            "Borrower SSN: 412-55-7531",
            "Card Number: 4000 0566 5566 5556",
            "Date of Birth: 03/14/1962",
            "End of page"
    };

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("usage: MakeCasePdf <out.pdf> typed|scanned <pages>");
        boolean scanned = "scanned".equals(args[1]);
        // Task 0064: every page from the second also carries a pen mark, which the routing checks send to vision.
        boolean marked = "marked".equals(args[1]);
        int pages = Integer.parseInt(args[2]);
        try (PDDocument document = new PDDocument()) {
            for (int n = 1; n <= pages; n++) {
                // A large document has them on pages 19, 20, 38, 39 and so on too: pages two page batches share (task 0049).
                String[] lines = n == 1 || (pages > 20 && n % 19 <= 1) ? VALUES : new String[]{
                        "Supporting page " + n + " of the loan file",
                        "Monthly income is reviewed every year by the lender",
                        "The borrower agrees to the terms on the first page",
                        "End of page"};
                PDPage page = new PDPage(PDRectangle.LETTER);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    if (scanned) {
                        PDImageXObject image = LosslessFactory.createFromImage(document, picture(lines));
                        content.drawImage(image, 0, 0, PDRectangle.LETTER.getWidth(), PDRectangle.LETTER.getHeight());
                    } else {
                        content.beginText();
                        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        content.setLeading(24);
                        content.newLineAtOffset(72, 700);
                        for (String line : lines) {
                            content.showText(line);
                            content.newLine();
                        }
                        content.endText();
                        if (marked && n > 1) {
                            content.setLineWidth(1.6f);
                            content.moveTo(300, 190);
                            for (int i = 1; i <= 8; i++) content.lineTo(300 + 9 * i, i % 2 == 0 ? 187 + i : 206 - i);
                            content.stroke();
                        }
                    }
                }
            }
            document.save(new File(args[0]));
        }
    }

    /** The page as a 150 DPI picture, black text on white, as a scanner would give it. */
    private static BufferedImage picture(String[] lines) {
        BufferedImage image = new BufferedImage(1275, 1650, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.setColor(Color.BLACK);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(new Font("SansSerif", Font.PLAIN, 26));
            int y = 200;
            for (String line : lines) {
                g.drawString(line, 150, y);
                y += 50;
            }
        } finally {
            g.dispose();
        }
        return image;
    }
}
