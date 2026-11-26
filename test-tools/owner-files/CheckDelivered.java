import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Task 0099: reads every delivered PDF as a reader would (its text layer, and Tesseract OCR of every page at 220 dpi)
 * and says, per file, which kinds of the owner's synthetic values are still in it. Prints kinds only, never a value.
 *
 *   java -cp <pdfbox, fontbox, pdfbox-io, commons-logging> CheckDelivered.java <file.pdf>...
 *
 * The values are the synthetic ones of C:\dev\lexavault\test-pdfs (README.md and long\*-expected.txt): every value of a
 * kind on by default for a new entity (SSN, date of birth, card number, card security code, credit score).
 */
public class CheckDelivered {

    private static final Map<String, List<String>> VALUES = new LinkedHashMap<>();
    static {
        VALUES.put("SSN", List.of("512-34-8876", "287-65-4321", "318-40-2295", "401-22-7731"));
        VALUES.put("DOB", List.of("03/14/1979", "11/02/1981", "01/25/1966", "07/09/1990"));
        VALUES.put("CARD", List.of("4111 1111 1111 1111"));
    }
    private static final Pattern CVV = Pattern.compile("(?i)CVV\\W{0,3}1\\W?2\\W?3(?!\\d)");
    private static final Pattern SCORE = Pattern.compile("(?i)score[^0-9]{0,20}742(?!\\d)");

    public static void main(String[] args) throws Exception {
        int leaks = 0;
        for (String file : args) {
            String text;
            String ocr;
            try (PDDocument doc = Loader.loadPDF(new File(file))) {
                text = new PDFTextStripper().getText(doc);
                ocr = ocr(doc);
            }
            Set<String> where = new LinkedHashSet<>();
            for (Map.Entry<String, String> read : Map.of("text", text, "ocr", ocr).entrySet()) {
                for (String kind : kinds(read.getValue())) where.add(kind + "(" + read.getKey() + ")");
            }
            if (!where.isEmpty()) leaks++;
            System.out.println((where.isEmpty() ? "CLEAN " : "LEAK  ") + Path.of(file).getFileName()
                    + (where.isEmpty() ? "" : " " + where));
        }
        System.out.println(leaks == 0 ? "ALL CLEAN" : leaks + " WITH A VALUE");
        System.exit(leaks == 0 ? 0 : 1);
    }

    static Set<String> kinds(String text) {
        Set<String> found = new LinkedHashSet<>();
        List<String> words = new ArrayList<>();
        for (String w : text.split("\\s+")) {
            String c = w.replaceAll("[^0-9A-Za-z]", "").toLowerCase();
            if (!c.isEmpty()) words.add(c);
        }
        VALUES.forEach((kind, values) -> {
            for (String value : values) if (appears(value.replaceAll("[^0-9]", ""), words)) found.add(kind);
        });
        if (CVV.matcher(text).find()) found.add("CVV");
        if (SCORE.matcher(text).find()) found.add("SCORE");
        return found;
    }

    /** A run of words that makes up the value, or one word that holds it (as the product's own final check). */
    private static boolean appears(String value, List<String> words) {
        for (int i = 0; i < words.size(); i++) {
            if (words.get(i).contains(value)) return true;
            StringBuilder run = new StringBuilder();
            for (int j = i; j < words.size() && run.length() < value.length(); j++) {
                run.append(words.get(j));
                if (run.toString().equals(value)) return true;
            }
        }
        return false;
    }

    private static String ocr(PDDocument doc) throws Exception {
        PDFRenderer renderer = new PDFRenderer(doc);
        StringBuilder all = new StringBuilder();
        Path dir = Files.createTempDirectory("lv-0099-check");
        try {
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                Path png = dir.resolve("p" + i + ".png");
                ImageIO.write(renderer.renderImageWithDPI(i, 220, ImageType.RGB), "png", png.toFile());
                Process p = new ProcessBuilder("tesseract", png.toString(), "stdout", "--psm", "3")
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                all.append(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).append('\n');
                p.waitFor();
                Files.deleteIfExists(png);
            }
        } finally {
            Files.deleteIfExists(dir);
        }
        return all.toString();
    }
}
