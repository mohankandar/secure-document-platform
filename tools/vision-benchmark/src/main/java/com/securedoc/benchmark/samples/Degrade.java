package com.securedoc.benchmark.samples;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.util.Random;

/** Turns a clean page image into what a scanner or a fax machine produces. */
final class Degrade {

    private Degrade() {
    }

    /** A clean office scan: a slight tilt and a little sensor noise. */
    static BufferedImage cleanScan(BufferedImage page, Random random) {
        BufferedImage out = rotate(page, (random.nextDouble() - 0.5) * 0.8);
        return noise(out, random, 6);
    }

    /** A poor scan: blurred, washed out, noisy and tilted. */
    static BufferedImage poorScan(BufferedImage page, Random random) {
        BufferedImage out = rotate(page, (random.nextDouble() - 0.5) * 2.4);
        out = blur(blur(out));
        out = contrast(out, 95, 215);
        return noise(out, random, 22);
    }

    /**
     * A fax: resampled to 204 x 98 dpi (standard fax resolution), thresholded to black and white,
     * speckled, then scaled back to the page size, with a fax header line at the top.
     */
    static BufferedImage fax(BufferedImage page, Random random, String header) {
        BufferedImage tilted = rotate(page, (random.nextDouble() - 0.5) * 1.6);
        int w = (int) Math.round(8.5 * 204), h = (int) Math.round(11 * 98);
        BufferedImage low = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = low.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(tilted, 0, 0, w, h, null);
        g.dispose();
        BufferedImage bw = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int lum = low.getRaster().getSample(x, y, 0);
                boolean black = lum < 150;
                double r = random.nextDouble();
                if (r < 0.003) black = !black;           // speckle
                bw.setRGB(x, y, black ? 0xFF000000 : 0xFFFFFFFF);
            }
        }
        // drop-outs: a few thin white streaks, as a fax line glitch makes
        for (int i = 0; i < 3; i++) {
            int y = random.nextInt(h);
            for (int x = 0; x < w; x++) bw.setRGB(x, y, 0xFFFFFFFF);
        }
        BufferedImage up = new BufferedImage(page.getWidth(), page.getHeight(), BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D u = up.createGraphics();
        u.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        u.drawImage(bw, 0, 0, page.getWidth(), page.getHeight(), null);
        u.setColor(Color.BLACK);
        u.setFont(new Font("Courier New", Font.PLAIN, Math.max(10, page.getWidth() / 75)));
        u.drawString(header, page.getWidth() / 40, page.getHeight() / 90);
        u.dispose();
        return up;
    }

    static BufferedImage rotate(BufferedImage src, double degrees) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, AffineTransform.getRotateInstance(Math.toRadians(degrees), src.getWidth() / 2.0, src.getHeight() / 2.0), null);
        g.dispose();
        return out;
    }

    static BufferedImage blur(BufferedImage src) {
        float[] k = new float[9];
        java.util.Arrays.fill(k, 1f / 9f);
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        new ConvolveOp(new Kernel(3, 3, k), ConvolveOp.EDGE_NO_OP, null).filter(src, out);
        return out;
    }

    static BufferedImage contrast(BufferedImage src, int low, int high) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int rgb = src.getRGB(x, y);
                int r = low + ((rgb >> 16 & 0xff) * (high - low)) / 255;
                int gr = low + ((rgb >> 8 & 0xff) * (high - low)) / 255;
                int b = low + ((rgb & 0xff) * (high - low)) / 255;
                out.setRGB(x, y, (r << 16) | (gr << 8) | b);
            }
        }
        return out;
    }

    static BufferedImage noise(BufferedImage src, Random random, int sigma) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int rgb = src.getRGB(x, y);
                int n = (int) (random.nextGaussian() * sigma);
                int r = clamp((rgb >> 16 & 0xff) + n), gr = clamp((rgb >> 8 & 0xff) + n), b = clamp((rgb & 0xff) + n);
                out.setRGB(x, y, (r << 16) | (gr << 8) | b);
            }
        }
        return out;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
