package com.securedoc.platform.infrastructure.extraction;

import com.securedoc.platform.application.port.out.TextExtractionPort.VisualRegion;
import com.securedoc.platform.application.port.out.TextExtractionPort.VisualRegionType;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Detects compact, near-solid dark rectangles in a rendered page.
 *
 * <p>The detector is intentionally content-agnostic. It does not know about SSNs, DOBs,
 * payment cards, or any other sensitive type. It emits only safe normalized geometry so the
 * application layer can decide whether a strong OCR field label is already visually protected.
 * Page pixels and OCR text are never persisted or logged by this component.</p>
 */
final class SolidDarkRectangleDetector {

    private static final int DARK_LUMINANCE_MAX = 85;
    private static final double MIN_NORMALIZED_WIDTH = 0.015;
    private static final double MIN_NORMALIZED_HEIGHT = 0.004;
    private static final double MAX_NORMALIZED_HEIGHT = 0.060;
    private static final double MIN_FILL_RATIO = 0.82;
    private static final double MIN_ASPECT_RATIO = 1.40;
    private static final int MAX_REGIONS = 128;

    private SolidDarkRectangleDetector() {
    }

    static List<VisualRegion> detect(BufferedImage image) {
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            return List.of();
        }

        int width = image.getWidth();
        int height = image.getHeight();
        int pixelCount = width * height;
        boolean[] visited = new boolean[pixelCount];
        int[] queue = new int[pixelCount];
        List<VisualRegion> regions = new ArrayList<>();

        int minWidthPx = Math.max(8, (int) Math.ceil(width * MIN_NORMALIZED_WIDTH));
        int minHeightPx = Math.max(4, (int) Math.ceil(height * MIN_NORMALIZED_HEIGHT));
        int maxHeightPx = Math.max(minHeightPx, (int) Math.ceil(height * MAX_NORMALIZED_HEIGHT));

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                if (visited[index] || !isDark(image.getRGB(x, y))) {
                    continue;
                }

                int head = 0;
                int tail = 0;
                queue[tail++] = index;
                visited[index] = true;

                int minX = x;
                int maxX = x;
                int minY = y;
                int maxY = y;
                int darkPixels = 0;

                while (head < tail) {
                    int current = queue[head++];
                    int cy = current / width;
                    int cx = current - (cy * width);
                    darkPixels++;

                    if (cx < minX) minX = cx;
                    if (cx > maxX) maxX = cx;
                    if (cy < minY) minY = cy;
                    if (cy > maxY) maxY = cy;

                    if (cx > 0) {
                        tail = enqueueIfDark(image, visited, queue, tail, current - 1, cx - 1, cy, width);
                    }
                    if (cx + 1 < width) {
                        tail = enqueueIfDark(image, visited, queue, tail, current + 1, cx + 1, cy, width);
                    }
                    if (cy > 0) {
                        tail = enqueueIfDark(image, visited, queue, tail, current - width, cx, cy - 1, width);
                    }
                    if (cy + 1 < height) {
                        tail = enqueueIfDark(image, visited, queue, tail, current + width, cx, cy + 1, width);
                    }
                }

                int componentWidth = maxX - minX + 1;
                int componentHeight = maxY - minY + 1;
                if (componentWidth < minWidthPx || componentHeight < minHeightPx || componentHeight > maxHeightPx) {
                    continue;
                }

                double fillRatio = (double) darkPixels / ((double) componentWidth * componentHeight);
                double aspectRatio = (double) componentWidth / componentHeight;
                if (fillRatio < MIN_FILL_RATIO || aspectRatio < MIN_ASPECT_RATIO) {
                    continue;
                }

                double normalizedLeft = (double) minX / width;
                double normalizedTop = (double) minY / height;
                double normalizedWidth = (double) componentWidth / width;
                double normalizedHeight = (double) componentHeight / height;
                double confidence = Math.min(1.0, 0.80 + (fillRatio * 0.20));
                regions.add(new VisualRegion(
                        VisualRegionType.SOLID_DARK_RECTANGLE,
                        normalizedLeft,
                        normalizedTop,
                        normalizedWidth,
                        normalizedHeight,
                        confidence));

                if (regions.size() >= MAX_REGIONS) {
                    return sorted(regions);
                }
            }
        }

        return sorted(regions);
    }

    private static int enqueueIfDark(BufferedImage image,
                                     boolean[] visited,
                                     int[] queue,
                                     int tail,
                                     int index,
                                     int x,
                                     int y,
                                     int width) {
        if (!visited[index] && isDark(image.getRGB(x, y))) {
            visited[index] = true;
            queue[tail++] = index;
        }
        return tail;
    }

    private static boolean isDark(int rgb) {
        int red = (rgb >> 16) & 0xff;
        int green = (rgb >> 8) & 0xff;
        int blue = rgb & 0xff;
        int luminance = (red * 2126 + green * 7152 + blue * 722) / 10000;
        return luminance <= DARK_LUMINANCE_MAX;
    }

    private static List<VisualRegion> sorted(List<VisualRegion> regions) {
        return regions.stream()
                .sorted(Comparator
                        .comparingDouble(VisualRegion::top)
                        .thenComparingDouble(VisualRegion::left))
                .toList();
    }
}
