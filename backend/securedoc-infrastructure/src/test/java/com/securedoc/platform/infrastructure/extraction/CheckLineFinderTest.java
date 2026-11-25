package com.securedoc.platform.infrastructure.extraction;

import com.securedoc.platform.domain.finding.FindingLocation.BoundingBox;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0092: a check's line by its shape. The page is drawn here; the digits are invented. */
class CheckLineFinderTest {
    private static final int W = 1700;
    private static final int H = 2200;
    private static final double PITCH = 0.125 * 200;

    @Test
    void bothNumbersOfAPersonalAndABusinessCheckAreFoundAtTheirPlaces() {
        for (String line : List.of("T123456780T  4455667788O  1021", "O001234O T123456780T 99887766O", "T123456780T 4455667788O 1021")) {
            for (double tilt : new double[]{0, 1, -1}) {
                BufferedImage page = page(line, 1, tilt);
                List<CheckLineFinder.Line> found = CheckLineFinder.find(page);
                assertEquals(1, found.size(), line + " " + tilt);
                int routing = line.indexOf("123456780");
                int account = line.contains("4455667788") ? line.indexOf("4455667788") : line.indexOf("99887766");
                assertTrue(holds(found.get(0).routing(), routing + 4, tilt), "routing of " + line);
                assertTrue(holds(found.get(0).account(), account + 4, tilt), "account of " + line);
            }
        }
    }

    @Test
    void aSmallerCopyIsFoundAndACoveredLineIsNot() {
        assertEquals(1, CheckLineFinder.find(page("T123456780T  4455667788O  1021", 0.85, 0)).size());
        BufferedImage covered = page("T123456780T  4455667788O  1021", 1, 0);
        Graphics2D g = covered.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(190, 1150, 700, 80);
        assertTrue(CheckLineFinder.find(covered).isEmpty());
    }

    @Test
    void ordinaryTextIsNoCheckLine() {
        BufferedImage page = new BufferedImage(W, H, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = page.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 30));
        for (int y = 200; y < 2000; y += 60) g.drawString("The borrower agrees to the terms on 10/14/2026, phone 555 010 4477.", 150, y);
        assertTrue(CheckLineFinder.find(page).isEmpty());
        assertTrue(CheckLineFinder.find(new BufferedImage(40, 40, BufferedImage.TYPE_BYTE_GRAY)).isEmpty());
    }

    /** Bars stand for the characters: only the shape counts. T is a transit mark, O an on-us mark. */
    private static BufferedImage page(String line, double size, double tilt) {
        BufferedImage page = new BufferedImage(W, H, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = page.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.rotate(Math.toRadians(tilt), W / 2.0, H / 2.0);
        g.setColor(Color.BLACK);
        double tall = 0.117 * 200 * size;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            double x = 200 + i * PITCH * size;
            double y = 1200 - tall;
            if (c == ' ') continue;
            if (c == 'T' || c == 'O') {
                g.fill(new Rectangle2D.Double(x + 2, y, 4, tall));
                g.fill(new Rectangle2D.Double(x + 11, y, 5, tall * 0.35));
            } else {
                g.fill(new Rectangle2D.Double(x + 4, y, 3, tall));
                g.fill(new Rectangle2D.Double(x + 4, y, 13 - (c - '0') % 3, 3));
                g.fill(new Rectangle2D.Double(x + 4, y + tall - 3, 13, 3));
            }
        }
        return page;
    }

    private static boolean holds(BoundingBox box, int position, double tilt) {
        var at = java.awt.geom.AffineTransform.getRotateInstance(Math.toRadians(tilt), W / 2.0, H / 2.0)
                .transform(new java.awt.geom.Point2D.Double(200 + (position + 0.5) * PITCH, 1190), null);
        double x = at.getX() / W;
        double y = at.getY() / H;
        return x > box.left() && x < box.left() + box.width() && y > box.top() && y < box.top() + box.height();
    }
}
