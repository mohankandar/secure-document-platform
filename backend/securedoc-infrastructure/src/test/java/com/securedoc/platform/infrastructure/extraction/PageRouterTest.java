package com.securedoc.platform.infrastructure.extraction;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0064: the benchmark router checks, with their own tests. */
class PageRouterTest {

    @Test
    void layerAgreementNeedsTheSameWordInTheSamePlace() {
        List<PageRouter.Word> layer = new java.util.ArrayList<>();
        List<PageRouter.Word> same = new java.util.ArrayList<>();
        List<PageRouter.Word> moved = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            layer.add(new PageRouter.Word("word" + i, 0.1, 0.05 * i, 0.05, 0.01, 1));
            same.add(new PageRouter.Word("WORD" + i + ",", 0.101, 0.05 * i, 0.05, 0.01, 0.9));
            moved.add(new PageRouter.Word("word" + i, 0.6, 0.05 * ((i + 6) % 12), 0.05, 0.01, 0.9));
        }
        assertEquals(1.0, PageRouter.agreement(layer, same), 1e-9);
        assertEquals(0.0, PageRouter.agreement(layer, moved), 1e-9);
        assertEquals(-1, PageRouter.agreement(layer.subList(0, 5), same), "too few words to judge");
    }

    @Test
    void aSignatureLabelToTheLeftOrBelowMakesInkASignature() {
        double[] region = {0.4, 0.80, 0.2, 0.04};
        assertTrue(PageRouter.nearSignatureLabel(region, List.of(new PageRouter.Word("Signature:", 0.1, 0.81, 0.1, 0.015, 1))));
        assertTrue(PageRouter.nearSignatureLabel(region, List.of(new PageRouter.Word("initials", 0.42, 0.845, 0.08, 0.015, 1))));
        assertFalse(PageRouter.nearSignatureLabel(region, List.of(new PageRouter.Word("Phone:", 0.1, 0.81, 0.1, 0.015, 1))));
        assertFalse(PageRouter.nearSignatureLabel(region, List.of(new PageRouter.Word("Signature:", 0.1, 0.30, 0.1, 0.015, 1))));
    }

    @Test
    void aColouredBlockIsAPictureAndBlackTextIsNot() {
        BufferedImage page = new BufferedImage(612, 792, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = page.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 612, 792);
        g.setColor(Color.BLACK);
        g.setFont(new java.awt.Font("Arial", java.awt.Font.PLAIN, 9));
        for (int y = 20; y < 300; y += 13) g.drawString("Plain typed text line that is not a picture at all.", 30, y);
        g.setColor(new Color(200, 225, 240));
        g.fillRect(100, 400, 250, 160);
        g.dispose();
        List<double[]> regions = PageRouter.pictureRegions(page);
        assertEquals(1, regions.size());
        assertTrue(regions.get(0)[1] > 0.45, "the region is the block, not the text");
    }

    @Test
    void unexplainedInkIsFoundAndExplainedInkAndLinesAreNot() {
        BufferedImage page = new BufferedImage(850, 1100, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = page.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 850, 1100);
        g.setColor(Color.BLACK);
        g.fillRect(50, 500, 700, 2);                      // a form line
        g.setFont(new java.awt.Font("Arial", java.awt.Font.PLAIN, 14));
        g.drawString("Typed words", 100, 200);            // explained below
        g.setStroke(new java.awt.BasicStroke(2));
        g.drawPolyline(new int[]{400, 420, 440, 460, 480, 500}, new int[]{700, 680, 710, 675, 705, 690}, 6); // a scribble
        g.dispose();
        PageRouter.Word typed = new PageRouter.Word("Typed", 95 / 850.0, 180 / 1100.0, 100 / 850.0, 30 / 1100.0, 0.95);
        List<double[]> regions = PageRouter.inkRegions(page, List.of(typed), List.of());
        assertEquals(1, regions.size());
        assertTrue(regions.get(0)[1] > 0.6 && regions.get(0)[1] < 0.65);
    }

    @Test
    void componentsJoinNearbyCells() {
        boolean[][] grid = new boolean[5][10];
        grid[1][1] = true;
        grid[1][3] = true;
        grid[4][9] = true;
        assertEquals(3, PageRouter.components(grid, 0).size());
        assertEquals(2, PageRouter.components(grid, 1).size());
    }
}
