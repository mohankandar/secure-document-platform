package com.securedoc.benchmark.samples;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * One synthetic page, in PDF points (612 x 792, origin top-left, y grows downwards). Elements draw
 * themselves with Java2D and report where each sensitive item landed, so the answer file is made from
 * the same drawing as the page.
 */
final class PageSpec {

    static final double WIDTH = 612;
    static final double HEIGHT = 792;

    /** How the page is written to the PDF. */
    enum Mode {
        /** Typed text as real PDF text; handwriting, signatures and cards as images on top. */
        DIGITAL,
        /** The whole page as one image, as a clean scanner makes it. */
        SCAN_CLEAN,
        /** The whole page as one image: blurred, low contrast, noisy. */
        SCAN_POOR,
        /** The whole page as one image at fax resolution, black and white, with noise. */
        FAX
    }

    final String kind;
    final String expectedRoute;
    final Mode mode;
    final List<El> elements = new ArrayList<>();
    /** Scanned pages only: an invisible text layer that does NOT match the image (disagreement test). */
    List<Text> hiddenTextLayer;

    PageSpec(String kind, String expectedRoute, Mode mode) {
        this.kind = kind;
        this.expectedRoute = expectedRoute;
        this.mode = mode;
    }

    PageSpec add(El element) {
        elements.add(element);
        return this;
    }

    // --- elements --------------------------------------------------------------------------------

    /** Where an element reports a sensitive item (points, top-left origin). */
    interface Sink {
        void item(String type, Rectangle2D box, boolean handwritten);
    }

    sealed interface El permits Text, Line, Hand, Sig, Card, Photo {
    }

    /** Typed text; {@code y} is the baseline. */
    record Text(double x, double y, double size, boolean bold, String text, String truthType) implements El {
        Text(double x, double y, double size, String text) {
            this(x, y, size, false, text, null);
        }
    }

    record Line(double x1, double y1, double x2, double y2) implements El {
    }

    /** Handwritten words: a handwriting font with per-letter jitter, in ink colour. */
    record Hand(double x, double y, double size, String text, Color ink, String truthType, long seed) implements El {
    }

    /** A signature or initials: a scribble with no readable letters. */
    record Sig(double x, double y, double w, double h, long seed) implements El {
    }

    enum CardKind { DRIVER_LICENCE, ID_CARD, CHECK }

    /** An ID card or a check, drawn as a picture (it becomes an image in a digital PDF too). */
    record Card(double x, double y, double w, double h, CardKind kind, FakeData.Person person,
                String date, String amount, String checkNumber, long seed) implements El {
    }

    /** A photo with no item in it (a house photo in an appraisal, for example). */
    record Photo(double x, double y, double w, double h, long seed) implements El {
    }

    /** The rectangle (points) an element may draw into; raster elements become an image of this size. */
    static Rectangle2D frame(El el) {
        return switch (el) {
            case Hand h -> new Rectangle2D.Double(h.x() - 4, h.y() - h.size() * 1.3,
                    h.text().length() * h.size() * 0.75 + 16, h.size() * 1.9);
            case Sig s -> new Rectangle2D.Double(s.x() - 6, s.y() - 6, s.w() + 12, s.h() + 12);
            case Card c -> new Rectangle2D.Double(c.x(), c.y(), c.w(), c.h());
            case Photo p -> new Rectangle2D.Double(p.x(), p.y(), p.w(), p.h());
            default -> throw new IllegalArgumentException("not a raster element");
        };
    }

    static boolean isRaster(El el) {
        return el instanceof Hand || el instanceof Sig || el instanceof Card || el instanceof Photo;
    }

    // --- drawing ---------------------------------------------------------------------------------

    static final String TYPED_FONT = "Arial";

    static void prepare(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    static void draw(El el, Graphics2D g, Sink sink) {
        switch (el) {
            case Text t -> drawText(t, g, sink);
            case Line l -> {
                g.setColor(Color.BLACK);
                g.setStroke(new BasicStroke(0.7f));
                g.draw(new java.awt.geom.Line2D.Double(l.x1(), l.y1(), l.x2(), l.y2()));
            }
            case Hand h -> drawHand(h, g, sink);
            case Sig s -> drawSignature(s, g, sink);
            case Card c -> {
                switch (c.kind()) {
                    case DRIVER_LICENCE, ID_CARD -> drawIdCard(c, g, sink);
                    case CHECK -> drawCheck(c, g, sink);
                }
            }
            case Photo p -> drawPhoto(p.x(), p.y(), p.w(), p.h(), new Random(p.seed()), g, false);
        }
    }

    static Font typedFont(double size, boolean bold) {
        return new Font(TYPED_FONT, bold ? Font.BOLD : Font.PLAIN, 1).deriveFont((float) size);
    }

    /** The box of typed text: from the font's ascent to its descent. Arial has Helvetica's metrics. */
    static Rectangle2D typedBox(Text t) {
        Font font = typedFont(t.size(), t.bold());
        FontRenderContext frc = new FontRenderContext(null, true, true);
        Rectangle2D bounds = font.getStringBounds(t.text(), frc);
        return new Rectangle2D.Double(t.x(), t.y() + bounds.getY(), bounds.getWidth(), bounds.getHeight());
    }

    private static void drawText(Text t, Graphics2D g, Sink sink) {
        g.setColor(Color.BLACK);
        g.setFont(typedFont(t.size(), t.bold()));
        g.drawString(t.text(), (float) t.x(), (float) t.y());
        if (t.truthType() != null) {
            sink.item(t.truthType(), typedBox(t), false);
        }
    }

    private static final String[] HAND_FONTS = {"Ink Free", "Segoe Print", "Lucida Handwriting"};

    static Font handFont(long seed, double size) {
        String family = HAND_FONTS[(int) Math.floorMod(seed, HAND_FONTS.length)];
        Font font = new Font(family, Font.PLAIN, 1);
        if (!font.getFamily().equalsIgnoreCase(family)) {
            font = new Font(Font.SERIF, Font.ITALIC, 1);
        }
        return font.deriveFont((float) size);
    }

    private static void drawHand(Hand h, Graphics2D g, Sink sink) {
        Random random = new Random(h.seed());
        Font font = handFont(h.seed(), h.size());
        FontRenderContext frc = g.getFontRenderContext();
        g.setColor(h.ink());
        double x = h.x();
        Rectangle2D union = null;
        for (int i = 0; i < h.text().length(); i++) {
            String ch = String.valueOf(h.text().charAt(i));
            double scale = 0.9 + random.nextDouble() * 0.22;
            double angle = (random.nextDouble() - 0.5) * 0.22;
            double dy = (random.nextDouble() - 0.5) * h.size() * 0.14;
            Font glyphFont = font.deriveFont((float) (h.size() * scale));
            GlyphVector gv = glyphFont.createGlyphVector(frc, ch);
            AffineTransform at = AffineTransform.getTranslateInstance(x, h.y() + dy);
            at.rotate(angle);
            java.awt.Shape shape = at.createTransformedShape(gv.getOutline());
            g.fill(shape);
            if (!ch.isBlank()) {
                Rectangle2D b = shape.getBounds2D();
                union = union == null ? b : union.createUnion(b);
            }
            x += gv.getLogicalBounds().getWidth() * (0.92 + random.nextDouble() * 0.16);
        }
        if (h.truthType() != null && union != null) {
            sink.item(h.truthType(), union, true);
        }
    }

    private static void drawSignature(Sig s, Graphics2D g, Sink sink) {
        Random random = new Random(s.seed());
        g.setColor(random.nextBoolean() ? new Color(20, 30, 120) : new Color(15, 15, 15));
        g.setStroke(new BasicStroke((float) (0.9 + random.nextDouble() * 0.8), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D path = new Path2D.Double();
        double midY = s.y() + s.h() * 0.55;
        double x = s.x();
        path.moveTo(x, midY);
        int loops = Math.max(2, (int) (s.w() / Math.max(6, s.h() * 0.55)));
        double step = s.w() / loops;
        for (int i = 0; i < loops; i++) {
            double up = s.y() + random.nextDouble() * s.h() * 0.35;
            double down = s.y() + s.h() * (0.65 + random.nextDouble() * 0.35);
            path.curveTo(x + step * 0.3, up, x + step * 0.9, up, x + step * 0.55, midY);
            path.curveTo(x + step * 0.2, down, x + step * 0.8, down, x + step, midY + (random.nextDouble() - 0.5) * s.h() * 0.3);
            x += step;
        }
        path.curveTo(x + 4, midY - s.h() * 0.2, s.x() + s.w() * 0.3, s.y() + s.h() * 0.95, s.x() - 2, s.y() + s.h() * 0.8);
        g.draw(path);
        Rectangle2D bounds = g.getStroke().createStrokedShape(path).getBounds2D();
        sink.item("SIGNATURE", bounds.createIntersection(frame(s)), true);
    }

    private static void drawIdCard(Card c, Graphics2D g, Sink sink) {
        Random random = new Random(c.seed());
        double x = c.x(), y = c.y(), w = c.w(), h = c.h();
        double u = w / 300.0; // card units: the layout is designed at 300 x 190
        RoundRectangle2D shape = new RoundRectangle2D.Double(x, y, w, h, 14 * u, 14 * u);
        Color a = c.kind() == CardKind.DRIVER_LICENCE ? new Color(196, 222, 244) : new Color(230, 214, 240);
        Color b = c.kind() == CardKind.DRIVER_LICENCE ? new Color(246, 226, 214) : new Color(214, 238, 222);
        g.setPaint(new GradientPaint((float) x, (float) y, a, (float) (x + w), (float) (y + h), b));
        g.fill(shape);
        // guilloche-like background lines
        g.setStroke(new BasicStroke((float) (0.35 * u)));
        g.setColor(new Color(150, 170, 200, 90));
        for (int i = 0; i < 14; i++) {
            Path2D wave = new Path2D.Double();
            double wy = y + h * (0.2 + i * 0.055);
            wave.moveTo(x, wy);
            for (double t = 0; t <= w; t += 6 * u) {
                wave.lineTo(x + t, wy + Math.sin((t / w) * Math.PI * 4 + i) * 4 * u);
            }
            g.draw(wave);
        }
        g.setColor(new Color(40, 40, 90));
        g.setStroke(new BasicStroke((float) (0.8 * u)));
        g.draw(shape);
        g.setColor(new Color(25, 45, 110));
        g.setFont(typedFont(13 * u, true));
        g.drawString(c.kind() == CardKind.DRIVER_LICENCE ? "SAMPLE STATE  DRIVER LICENCE" : "SAMPLE STATE  IDENTIFICATION CARD",
                (float) (x + 12 * u), (float) (y + 20 * u));
        g.setFont(typedFont(7 * u, false));
        g.drawString("SPECIMEN - NOT A REAL DOCUMENT", (float) (x + 12 * u), (float) (y + 30 * u));

        drawPhoto(x + 12 * u, y + 40 * u, 78 * u, 98 * u, random, g, true);
        sink.item("FACE_PHOTO", new Rectangle2D.Double(x + 12 * u, y + 40 * u, 78 * u, 98 * u), false);

        double fx = x + 102 * u;
        g.setColor(new Color(20, 20, 20));
        Text[] rows = {
                new Text(fx, y + 50 * u, 7 * u, false, c.kind() == CardKind.DRIVER_LICENCE ? "DL NO." : "ID NO.", null),
                new Text(fx + 34 * u, y + 50 * u, 11 * u, true, c.person().licence(), "GOVERNMENT_ID"),
                new Text(fx, y + 66 * u, 7 * u, false, "LN", null),
                new Text(fx + 16 * u, y + 66 * u, 10 * u, true, c.person().last().toUpperCase(), null),
                new Text(fx, y + 80 * u, 7 * u, false, "FN", null),
                new Text(fx + 16 * u, y + 80 * u, 10 * u, true, c.person().first().toUpperCase(), null),
                new Text(fx, y + 94 * u, 7 * u, false, c.person().street().toUpperCase(), null),
                new Text(fx, y + 104 * u, 7 * u, false, c.person().cityLine().toUpperCase(), null),
                new Text(fx, y + 120 * u, 7 * u, false, "DOB", null),
                new Text(fx + 20 * u, y + 120 * u, 10 * u, true, c.person().dob(), "DATE_OF_BIRTH"),
                new Text(fx + 100 * u, y + 120 * u, 7 * u, false, "EXP", null),
                new Text(fx + 118 * u, y + 120 * u, 8 * u, false, c.date(), null),
                new Text(fx, y + 134 * u, 7 * u, false, "SEX X   HGT 5-08   EYES BRN", null),
        };
        for (Text row : rows) {
            drawText(row, g, sink);
        }
        drawSignature(new Sig(fx, y + 146 * u, 90 * u, 22 * u, random.nextLong()), g, sink);
        // small ghost photo
        Graphics2D ghost = (Graphics2D) g.create();
        ghost.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.35f));
        drawPhoto(x + w - 50 * u, y + 130 * u, 32 * u, 40 * u, new Random(c.seed() + 7), ghost, true);
        ghost.dispose();
        sink.item("FACE_PHOTO", new Rectangle2D.Double(x + w - 50 * u, y + 130 * u, 32 * u, 40 * u), false);
    }

    private static void drawCheck(Card c, Graphics2D g, Sink sink) {
        Random random = new Random(c.seed());
        double x = c.x(), y = c.y(), w = c.w(), h = c.h();
        double u = w / 440.0; // designed at 440 x 190
        g.setPaint(new GradientPaint((float) x, (float) y, new Color(222, 240, 226), (float) (x + w), (float) (y + h), new Color(214, 230, 242)));
        g.fill(new Rectangle2D.Double(x, y, w, h));
        g.setStroke(new BasicStroke((float) (0.3 * u)));
        g.setColor(new Color(150, 190, 160, 110));
        for (int i = 0; i < 30; i++) {
            Path2D wave = new Path2D.Double();
            double wy = y + h * (i / 30.0);
            wave.moveTo(x, wy);
            for (double t = 0; t <= w; t += 5 * u) {
                wave.lineTo(x + t, wy + Math.sin((t / w) * Math.PI * 7 + i * 0.7) * 3 * u);
            }
            g.draw(wave);
        }
        g.setColor(new Color(60, 60, 60));
        g.setStroke(new BasicStroke((float) (0.6 * u)));
        g.draw(new Rectangle2D.Double(x, y, w, h));

        Color ink = random.nextBoolean() ? new Color(25, 35, 130) : new Color(20, 20, 20);
        Text[] typed = {
                new Text(x + 14 * u, y + 20 * u, 9 * u, true, c.person().name().toUpperCase(), null),
                new Text(x + 14 * u, y + 31 * u, 7 * u, false, c.person().street(), null),
                new Text(x + 14 * u, y + 41 * u, 7 * u, false, c.person().cityLine(), null),
                new Text(x + 180 * u, y + 22 * u, 10 * u, true, "SAMPLE COMMUNITY BANK", null),
                new Text(x + 395 * u, y + 20 * u, 10 * u, true, c.checkNumber(), null),
                new Text(x + 300 * u, y + 52 * u, 7 * u, false, "DATE", null),
                new Text(x + 14 * u, y + 82 * u, 7 * u, false, "PAY TO THE", null),
                new Text(x + 14 * u, y + 90 * u, 7 * u, false, "ORDER OF", null),
                new Text(x + 345 * u, y + 88 * u, 11 * u, true, "$", null),
                new Text(x + 380 * u, y + 118 * u, 7 * u, false, "DOLLARS", null),
                new Text(x + 14 * u, y + 150 * u, 7 * u, false, "MEMO", null),
                new Text(x + 10 * u, y + 30 * u, 1, false, "", null),
        };
        for (Text t : typed) {
            if (!t.text().isEmpty()) {
                drawText(t, g, sink);
            }
        }
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke((float) (0.6 * u)));
        g.draw(new java.awt.geom.Line2D.Double(x + 325 * u, y + 54 * u, x + 425 * u, y + 54 * u));
        g.draw(new java.awt.geom.Line2D.Double(x + 55 * u, y + 92 * u, x + 335 * u, y + 92 * u));
        g.draw(new Rectangle2D.Double(x + 355 * u, y + 74 * u, 72 * u, 20 * u));
        g.draw(new java.awt.geom.Line2D.Double(x + 14 * u, y + 120 * u, x + 375 * u, y + 120 * u));
        g.draw(new java.awt.geom.Line2D.Double(x + 40 * u, y + 152 * u, x + 200 * u, y + 152 * u));
        g.draw(new java.awt.geom.Line2D.Double(x + 250 * u, y + 152 * u, x + 425 * u, y + 152 * u));

        drawHand(new Hand(x + 330 * u, y + 50 * u, 11 * u, c.date(), ink, null, random.nextLong()), g, sink);
        drawHand(new Hand(x + 62 * u, y + 89 * u, 13 * u, "Sample Title Company", ink, null, random.nextLong()), g, sink);
        drawHand(new Hand(x + 360 * u, y + 90 * u, 11 * u, c.amount(), ink, null, random.nextLong()), g, sink);
        drawHand(new Hand(x + 20 * u, y + 117 * u, 11 * u, "Amount in words and 00/100", ink, null, random.nextLong()), g, sink);
        drawSignature(new Sig(x + 262 * u, y + 126 * u, 150 * u, 24 * u, random.nextLong()), g, sink);

        // MICR-style line: routing between transit symbols, then account, then check number.
        Font micr = new Font("OCR A Extended", Font.PLAIN, 1).deriveFont((float) (12 * u));
        g.setFont(micr);
        g.setColor(Color.BLACK);
        FontRenderContext frc = new FontRenderContext(null, true, true);
        double mx = x + 60 * u, my = y + 178 * u;
        String r = ":" + c.person().routing() + ":";
        g.drawString(r, (float) mx, (float) my);
        Rectangle2D rb = micr.getStringBounds(c.person().routing(), frc);
        double colon = micr.getStringBounds(":", frc).getWidth();
        sink.item("ROUTING_NUMBER", new Rectangle2D.Double(mx + colon, my + rb.getY(), rb.getWidth(), rb.getHeight()), false);
        double ax = mx + micr.getStringBounds(r + "  ", frc).getWidth();
        String acc = c.person().account();
        g.drawString(acc + "\"", (float) ax, (float) my);
        Rectangle2D ab = micr.getStringBounds(acc, frc);
        sink.item("BANK_ACCOUNT", new Rectangle2D.Double(ax, my + ab.getY(), ab.getWidth(), ab.getHeight()), false);
        double cx = ax + micr.getStringBounds(acc + "\"  ", frc).getWidth();
        g.drawString(c.checkNumber(), (float) cx, (float) my);
    }

    /** A synthetic portrait (shapes and noise, no real face) or a plain photo-like texture. */
    static void drawPhoto(double x, double y, double w, double h, Random random, Graphics2D g, boolean portrait) {
        Graphics2D p = (Graphics2D) g.create();
        p.clip(new Rectangle2D.Double(x, y, w, h));
        p.setPaint(new GradientPaint((float) x, (float) y, new Color(90 + random.nextInt(60), 110 + random.nextInt(60), 150 + random.nextInt(60)),
                (float) x, (float) (y + h), new Color(40 + random.nextInt(40), 60 + random.nextInt(40), 90 + random.nextInt(40))));
        p.fill(new Rectangle2D.Double(x, y, w, h));
        if (portrait) {
            Color skin = new Color(150 + random.nextInt(90), 110 + random.nextInt(70), 80 + random.nextInt(60));
            p.setColor(new Color(30 + random.nextInt(50), 30 + random.nextInt(40), 40 + random.nextInt(50)));
            p.fill(new Ellipse2D.Double(x + w * 0.05, y + h * 0.68, w * 0.9, h * 0.6));
            p.setColor(skin.darker());
            p.fill(new Rectangle2D.Double(x + w * 0.4, y + h * 0.55, w * 0.2, h * 0.2));
            p.setPaint(new GradientPaint((float) (x + w * 0.3), (float) (y + h * 0.2), skin.brighter(),
                    (float) (x + w * 0.7), (float) (y + h * 0.6), skin.darker()));
            p.fill(new Ellipse2D.Double(x + w * 0.25, y + h * 0.15, w * 0.5, h * 0.5));
            p.setColor(new Color(40 + random.nextInt(60), 25 + random.nextInt(40), 15 + random.nextInt(30)));
            p.fill(new java.awt.geom.Arc2D.Double(x + w * 0.22, y + h * 0.1, w * 0.56, h * 0.32, 0, 180, java.awt.geom.Arc2D.CHORD));
            p.setColor(new Color(30, 25, 25));
            p.fill(new Ellipse2D.Double(x + w * 0.37, y + h * 0.35, w * 0.07, h * 0.04));
            p.fill(new Ellipse2D.Double(x + w * 0.56, y + h * 0.35, w * 0.07, h * 0.04));
            p.setStroke(new BasicStroke((float) (w * 0.015)));
            p.draw(new java.awt.geom.Arc2D.Double(x + w * 0.42, y + h * 0.44, w * 0.16, h * 0.08, 200, 140, java.awt.geom.Arc2D.OPEN));
        } else {
            for (int i = 0; i < 40; i++) {
                p.setColor(new Color(random.nextInt(200), 80 + random.nextInt(150), random.nextInt(160), 140));
                double bw = w * (0.1 + random.nextDouble() * 0.4), bh = h * (0.1 + random.nextDouble() * 0.4);
                p.fill(new Ellipse2D.Double(x + random.nextDouble() * w - bw / 2, y + random.nextDouble() * h - bh / 2, bw, bh));
            }
        }
        // sensor noise, so it reads as a photo rather than flat shapes
        for (int i = 0; i < (int) (w * h / 6); i++) {
            p.setColor(new Color(random.nextInt(256), random.nextInt(256), random.nextInt(256), 28));
            p.fill(new Rectangle2D.Double(x + random.nextDouble() * w, y + random.nextDouble() * h, 0.8, 0.8));
        }
        p.dispose();
    }
}
