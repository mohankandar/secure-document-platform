import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Task 0017 test tool. Writes the text of a PDF (PDFBox) to a UTF-8 file, plus its page count on the first line.
 * Run with PDFBox on the class path:  java -cp <pdfbox jars> ExtractPdfText.java in.pdf out.txt
 */
public class ExtractPdfText {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("usage: ExtractPdfText <in.pdf> <out.txt>");
        try (PDDocument doc = Loader.loadPDF(new File(args[0]))) {
            String text = new PDFTextStripper().getText(doc);
            Files.writeString(new File(args[1]).toPath(), "pages=" + doc.getNumberOfPages() + "\n" + text,
                    StandardCharsets.UTF_8);
        }
    }
}
