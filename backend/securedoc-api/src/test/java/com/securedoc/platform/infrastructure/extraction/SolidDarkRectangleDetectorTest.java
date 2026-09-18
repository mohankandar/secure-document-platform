package com.securedoc.platform.infrastructure.extraction;

import com.securedoc.platform.application.port.out.TextExtractionPort.VisualRegionType;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SolidDarkRectangleDetectorTest {

    @Test
    void detectsCompactSolidBlackoutButIgnoresThinRulesAndSmallGlyphs() {
        BufferedImage image = new BufferedImage(600, 800, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());

            graphics.setColor(Color.BLACK);
            graphics.fillRect(250, 300, 90, 16);   // intended blackout
            graphics.fillRect(100, 420, 250, 2);   // table/rule line: too thin
            graphics.fillRect(120, 500, 5, 18);    // glyph-like mark: too narrow
        } finally {
            graphics.dispose();
        }

        var regions = SolidDarkRectangleDetector.detect(image);

        assertEquals(1, regions.size());
        var region = regions.getFirst();
        assertEquals(VisualRegionType.SOLID_DARK_RECTANGLE, region.type());
        assertTrue(region.left() > 0.40 && region.left() < 0.43);
        assertTrue(region.top() > 0.37 && region.top() < 0.39);
        assertTrue(region.width() > 0.14 && region.width() < 0.16);
        assertTrue(region.height() > 0.019 && region.height() < 0.022);
        assertTrue(region.confidence() >= 0.99);
    }
}
