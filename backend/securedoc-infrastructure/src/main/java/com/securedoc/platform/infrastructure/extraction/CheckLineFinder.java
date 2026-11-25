package com.securedoc.platform.infrastructure.extraction;

import com.securedoc.platform.domain.finding.FindingLocation.BoundingBox;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Task 0092: the machine-readable line of a check, found by its shape alone. The line is printed to a standard: every
 * character has the same pitch (an eighth of an inch) and height (0.117 inch), in a few groups with wide gaps. Nothing is
 * read: the result is places, never a digit. The size is taken from the page (a copy may be smaller), the proportion is not.
 *
 * <p>A group of about 9 characters (with its two transit marks) that is followed by another group is the routing number,
 * the group after it the account number.
 */
final class CheckLineFinder {
    /** Height to pitch is 0.94 in the standard. */
    private static final double MIN_PITCH_TO_HEIGHT = 0.92;
    private static final double MAX_PITCH_TO_HEIGHT = 1.30;
    private static final int MIN_HEIGHT = 12;
    private static final int MAX_HEIGHT = 90;
    /** Between the routing and the account number, in characters: at least one empty place. */
    private static final double MIN_GAP = 1.8;
    private static final int MIN_DIGITS = 14;
    /** A check line is short; a sentence is not. */
    private static final int MAX_CHARACTERS = 45;
    private static final int MAX_GROUPS = 5;
    /** The standard pitch as a share of a letter page's width, and how small a copy may be. */
    private static final double STANDARD_PITCH = 0.125 / 8.5;
    private static final double SMALLEST_COPY = 0.75;

    record Line(BoundingBox routing, BoundingBox account) { }

    private record Blob(int left, int top, int right, int bottom) {
        int height() {
            return bottom - top;
        }

        int width() {
            return right - left;
        }

        double centre() {
            return (left + right) / 2.0;
        }
    }

    private CheckLineFinder() {
    }

    static List<Line> find(BufferedImage image) {
        boolean[][] ink = ink(image);
        List<Blob> blobs = blobs(ink);
        blobs.sort(Comparator.comparingInt(Blob::top).thenComparingInt(Blob::left));
        List<Line> lines = new ArrayList<>();
        boolean[] used = new boolean[blobs.size()];
        for (int i = 0; i < blobs.size(); i++) {
            if (used[i]) continue;
            Blob first = blobs.get(i);
            // The characters of one height that stand on one line with this one.
            List<Integer> row = new ArrayList<>();
            for (int j = 0; j < blobs.size(); j++) {
                Blob b = blobs.get(j);
                if (!used[j] && Math.abs(b.bottom() - first.bottom()) <= first.height() * 0.35
                        && Math.abs(b.top() - first.top()) <= first.height() * 0.35) row.add(j);
            }
            if (row.size() < MIN_DIGITS || row.size() > MAX_CHARACTERS) continue;
            List<Blob> characters = row.stream().map(blobs::get).sorted(Comparator.comparingInt(Blob::left)).toList();
            Line line = line(characters, image.getWidth(), image.getHeight());
            if (line == null) continue;
            row.forEach(j -> used[j] = true);
            lines.add(line);
        }
        return lines;
    }

    private static Line line(List<Blob> characters, int width, int height) {
        double tall = median(characters.stream().map(b -> (double) b.height()).toList());
        List<Double> steps = new ArrayList<>();
        for (int i = 1; i < characters.size(); i++) {
            double step = characters.get(i).centre() - characters.get(i - 1).centre();
            if (step < tall * 1.6) steps.add(step);
        }
        if (steps.size() < MIN_DIGITS - 3) return null;
        double pitch = median(steps);
        if (pitch < tall * MIN_PITCH_TO_HEIGHT || pitch > tall * MAX_PITCH_TO_HEIGHT) return null;
        if (pitch < width * STANDARD_PITCH * SMALLEST_COPY) return null;
        // One pitch for the whole line: ordinary print has narrow and wide letters.
        long regular = steps.stream().filter(s -> Math.abs(s - pitch) <= pitch * 0.22).count();
        if (regular < steps.size() * 0.75) return null;
        List<List<Blob>> groups = new ArrayList<>();
        for (Blob b : characters) {
            if (b.width() > pitch * 1.15) return null;
            List<Blob> last = groups.isEmpty() ? null : groups.get(groups.size() - 1);
            if (last == null || b.centre() - last.get(last.size() - 1).centre() > pitch * 1.75) {
                groups.add(new ArrayList<>(List.of(b)));
            } else {
                last.add(b);
            }
        }
        if (groups.size() > MAX_GROUPS) return null;
        for (int i = 0; i + 1 < groups.size(); i++) {
            int routing = positions(groups.get(i), pitch);
            int account = positions(groups.get(i + 1), pitch);
            double gap = groups.get(i + 1).get(0).centre() - groups.get(i).get(groups.get(i).size() - 1).centre();
            if (routing >= 9 && routing <= 11 && account >= 5 && account <= 19 && gap >= pitch * MIN_GAP) {
                return new Line(box(groups.get(i), pitch, width, height), box(groups.get(i + 1), pitch, width, height));
            }
        }
        return null;
    }

    private static int positions(List<Blob> group, double pitch) {
        return (int) Math.round((group.get(group.size() - 1).centre() - group.get(0).centre()) / pitch) + 1;
    }

    private static BoundingBox box(List<Blob> group, double pitch, int width, int height) {
        double margin = pitch * 0.35;
        double left = Math.max(0, group.get(0).left() - margin);
        double right = Math.min(width, group.get(group.size() - 1).right() + margin);
        double top = Math.max(0, group.stream().mapToInt(Blob::top).min().orElseThrow() - margin);
        double bottom = Math.min(height, group.stream().mapToInt(Blob::bottom).max().orElseThrow() + margin);
        double l = left / width;
        double t = top / height;
        return BoundingBox.of(l, t, Math.min((right - left) / width, 1 - l), Math.min((bottom - top) / height, 1 - t));
    }

    private static double median(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }

    /** Dark against the mean of its surroundings, so a grey copy and a patterned check both work. */
    private static boolean[][] ink(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        long[][] sum = new long[h + 1][w + 1];
        int[][] grey = new int[h][w];
        for (int y = 0; y < h; y++) {
            long row = 0;
            for (int x = 0; x < w; x++) {
                int rgb = image.getRGB(x, y);
                int g = (((rgb >> 16) & 255) * 30 + ((rgb >> 8) & 255) * 59 + (rgb & 255) * 11) / 100;
                grey[y][x] = g;
                row += g;
                sum[y + 1][x + 1] = sum[y][x + 1] + row;
            }
        }
        int r = Math.max(12, w / 60);
        boolean[][] ink = new boolean[h][w];
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - r);
            int y1 = Math.min(h, y + r + 1);
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - r);
                int x1 = Math.min(w, x + r + 1);
                long mean = (sum[y1][x1] - sum[y0][x1] - sum[y1][x0] + sum[y0][x0]) / ((long) (y1 - y0) * (x1 - x0));
                ink[y][x] = grey[y][x] < mean * 0.65;
            }
        }
        return ink;
    }

    /** Connected ink of a character's size. */
    private static List<Blob> blobs(boolean[][] ink) {
        int h = ink.length;
        int w = ink[0].length;
        boolean[][] seen = new boolean[h][w];
        List<Blob> blobs = new ArrayList<>();
        int[] stack = new int[1024];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!ink[y][x] || seen[y][x]) continue;
                int left = x;
                int right = x;
                int top = y;
                int bottom = y;
                int size = 0;
                stack[size++] = y * w + x;
                seen[y][x] = true;
                while (size > 0) {
                    int at = stack[--size];
                    int cy = at / w;
                    int cx = at % w;
                    left = Math.min(left, cx);
                    right = Math.max(right, cx);
                    top = Math.min(top, cy);
                    bottom = Math.max(bottom, cy);
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int ny = cy + dy;
                            int nx = cx + dx;
                            if (ny < 0 || nx < 0 || ny >= h || nx >= w || !ink[ny][nx] || seen[ny][nx]) continue;
                            seen[ny][nx] = true;
                            if (size == stack.length) stack = java.util.Arrays.copyOf(stack, size * 2);
                            stack[size++] = ny * w + nx;
                        }
                    }
                }
                int tall = bottom - top + 1;
                int wide = right - left + 1;
                if (tall >= MIN_HEIGHT && tall <= MAX_HEIGHT && wide <= tall * 1.3) {
                    blobs.add(new Blob(left, top, right + 1, bottom + 1));
                }
            }
        }
        return blobs;
    }
}
