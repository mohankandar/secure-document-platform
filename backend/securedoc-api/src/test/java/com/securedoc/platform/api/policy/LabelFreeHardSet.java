package com.securedoc.platform.api.policy;

import com.securedoc.platform.domain.finding.EntityType;
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
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Task 0103: the hard test set, made by code (no file, no real value). One page per case: each kind with no label, a
 * far label, an unusual or old label, a Spanish label, in a table, in a running sentence, split across lines, with OCR
 * damage, printed in an image and written by hand (an image too). Plus decoys that must stay visible. Every value is
 * made up; card numbers are the issuers' published test numbers.
 */
final class LabelFreeHardSet {

    enum Variant { NO_LABEL, FAR_LABEL, OLD_LABEL, SPANISH, TABLE, SENTENCE, SPLIT, OCR_DAMAGE, IMAGE, HANDWRITTEN }

    enum Render { TEXT, IMAGE, HAND }

    /**
     * @param kind  null for a decoy
     * @param value as it is meant (before any OCR damage): what must be covered, or what must stay visible
     */
    record Case(EntityType kind, Variant variant, String value, List<String> lines, Render render, String name) {
        boolean decoy() {
            return kind == null;
        }
    }

    /** The parts of one kind's cases. */
    private record Kind(EntityType kind, String value, String label, String oldLabel, String spanish, String phrase,
                        String[] split, String damaged, String context) {
    }

    private static final String CARD_CONTEXT = "Card on file 4111 1111 1111 1111";

    private static final List<Kind> KINDS = List.of(
            new Kind(EntityType.SSN, "287-65-4321", "SSN", "Soc. Sec. No.", "Numero de Seguro Social",
                    "social security number", new String[]{"287-65-", "4321"}, "2B7-65-432l", null),
            new Kind(EntityType.CREDIT_CARD_NUMBER, "5555 5555 5555 4444", "Card number", "Card ending",
                    "Numero de tarjeta", "card", new String[]{"5555 5555", "5555 4444"}, "5555 5S55 5555 4444", null),
            new Kind(EntityType.CVV, "734", "CVV", "Sec. code", "Codigo de seguridad", "security code on the back",
                    null, "7 34", CARD_CONTEXT),
            new Kind(EntityType.PIN, "8264", "PIN", "Pin #", "NIP", "PIN", null, "82 64", CARD_CONTEXT),
            new Kind(EntityType.DATE_OF_BIRTH, "03/14/1979", "Date of birth", "B'date", "Fecha de nacimiento",
                    "was born on", new String[]{"03/14/", "1979"}, "O3/14/l979", null),
            new Kind(EntityType.BANK_ROUTING_NUMBER, "021000021", "Routing number", "Bnk Rte", "Numero de ruta",
                    "routing number", new String[]{"0210", "00021"}, "O21OOOO21", null),
            new Kind(EntityType.BANK_ACCOUNT_NUMBER, "4820193746", "Account number", "Acct.", "Numero de cuenta",
                    "account", new String[]{"48201", "93746"}, "482Ol93746", null),
            new Kind(EntityType.ITIN, "912-78-1234", "ITIN", "Indiv. Taxpayer ID", "Numero ITIN",
                    "individual taxpayer identification number", new String[]{"912-78-", "1234"}, "9l2-78-1234", null),
            new Kind(EntityType.EIN, "12-3456789", "EIN", "Fed. Employer No.", "Numero de identificacion patronal",
                    "employer identification number", new String[]{"12-", "3456789"}, "l2-3456789", null),
            new Kind(EntityType.DRIVERS_LICENSE_NUMBER, "D4417202", "Driver's license number", "Drv. Lic.",
                    "Licencia de conducir", "driver's license", new String[]{"D441", "7202"}, "D44l7202", null),
            new Kind(EntityType.PASSPORT_NUMBER, "X31958274", "Passport number", "PP No.", "Pasaporte", "passport",
                    new String[]{"X3195", "8274"}, "X3l958274", null),
            new Kind(EntityType.CREDIT_SCORE, "712", "Credit score", "Beacon", "Puntaje de credito", "credit score",
                    null, "7l2", null));

    private LabelFreeHardSet() {
    }

    static List<Case> cases() {
        List<Case> all = new ArrayList<>();
        for (Kind k : KINDS) {
            for (Variant variant : Variant.values()) {
                Case c = of(k, variant);
                if (c != null) all.add(c);
            }
        }
        all.addAll(decoys());
        return all;
    }

    private static Case of(Kind k, Variant variant) {
        String v = k.value();
        List<String> lines = new ArrayList<>();
        lines.add("Customer file review, case " + (k.kind().ordinal() + 11) + variant.ordinal());
        if (k.context() != null) lines.add(k.context());
        Render render = Render.TEXT;
        switch (variant) {
            case NO_LABEL -> lines.add(v);
            case FAR_LABEL -> {
                lines.add(k.label() + ":");
                lines.add("(given at the interview, see the notes below)");
                lines.add("The applicant brought the documents listed in section 4.");
                lines.add("Everything was checked against the originals.");
                lines.add(v);
            }
            case OLD_LABEL -> lines.add(k.oldLabel() + " " + v);
            case SPANISH -> lines.add(k.spanish() + ": " + v);
            case TABLE -> {
                lines.add("Name              Phone              " + k.label());
                lines.add("Ana Ruiz          (555) 201-3344     " + v);
            }
            case SENTENCE -> lines.add("For the record, the " + k.phrase() + " we have is " + v
                    + ", as she confirmed by phone.");
            case SPLIT -> {
                if (k.split() == null) return null;
                lines.add(k.label() + ": " + k.split()[0]);
                lines.add(k.split()[1]);
            }
            case OCR_DAMAGE -> lines.add(k.label() + ": " + k.damaged());
            case IMAGE -> {
                lines.add(k.label() + ": " + v);
                render = Render.IMAGE;
            }
            case HANDWRITTEN -> {
                lines.add(k.label() + " " + v);
                render = Render.HAND;
            }
        }
        lines.add("End of page");
        return new Case(k.kind(), variant, v, lines, render, k.kind() + "/" + variant);
    }

    /** What must stay visible: numbers and dates that are not one of the kinds. */
    private static List<Case> decoys() {
        String[][] pages = {
                {"Order number 4471923", "Order #A-20931 shipped"},
                {"Phone (555) 201-3344", "Fax 555-201-3345"},
                {"Loan amount $250,000.00", "Monthly payment $1,234.56"},
                {"Statement date: 10/31/2026", "Payment due 12/01/2026"},
                {"Signed on 10/28/2026", "Account opened 03/15/2019"},
                {"Springfield, IL 62704-1234", "Page 3 of 12"},
                {"Invoice 2026-00417", "Rate 6.875% for 360 months"},
                {"Quantity: 742 units", "Model year 1990"},
                {"Case No. 2:24-cv-01234", "Docket 24-1187"},
        };
        String[] values = {"4471923", "(555) 201-3344", "$1,234.56", "10/31/2026", "03/15/2019", "62704-1234",
                "2026-00417", "742", "24-1187"};
        List<Case> decoys = new ArrayList<>();
        for (int i = 0; i < pages.length; i++) {
            List<String> lines = new ArrayList<>();
            lines.add("Customer file review, notes " + (i + 1));
            lines.addAll(List.of(pages[i]));
            lines.add("End of page");
            decoys.add(new Case(null, null, values[i], lines, Render.TEXT, "decoy/" + (i + 1)));
            // Each decoy page's every number is checked, not only the named one.
        }
        return decoys;
    }

    // ------------------------------------------------------------------------------------------------ the PDF

    static byte[] pdf(List<Case> cases) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            Random random = new Random(103);
            for (Case c : cases) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                    if (c.render() == Render.TEXT) {
                        float y = 700;
                        for (String line : c.lines()) {
                            content.beginText();
                            content.setFont(font, 11);
                            content.newLineAtOffset(72, y);
                            content.showText(line);
                            content.endText();
                            y -= 18;
                        }
                    } else {
                        BufferedImage image = image(c.lines(), c.render() == Render.HAND, random);
                        PDImageXObject x = LosslessFactory.createFromImage(doc, image);
                        content.drawImage(x, 0, 0, PDRectangle.LETTER.getWidth(), PDRectangle.LETTER.getHeight());
                    }
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** A scanned page at 200 dpi: printed, or each letter a little turned and shifted, as by hand. */
    private static BufferedImage image(List<String> lines, boolean hand, Random random) {
        BufferedImage image = new BufferedImage(1700, 2200, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Font font = hand ? new Font(Font.SERIF, Font.ITALIC, 46) : new Font(Font.SANS_SERIF, Font.PLAIN, 34);
        int y = 260;
        for (String line : lines) {
            String text = line;
            if (!hand) {
                g.setFont(font);
                g.drawString(text, 200, y);
            } else {
                int x = 200;
                for (char ch : text.toCharArray()) {
                    double angle = (random.nextDouble() - 0.5) * 0.18;
                    Font turned = font.deriveFont(AffineTransform.getRotateInstance(angle));
                    g.setFont(turned);
                    int dy = random.nextInt(7) - 3;
                    g.drawString(String.valueOf(ch), x, y + dy);
                    x += g.getFontMetrics(font).charWidth(ch) + random.nextInt(3);
                }
            }
            y += hand ? 90 : 70;
        }
        g.dispose();
        return image;
    }
}
