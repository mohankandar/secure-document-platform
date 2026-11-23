package com.securedoc.benchmark.routing;

import com.securedoc.platform.application.port.out.OcrPort;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.DrawObject;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.state.Concatenate;
import org.apache.pdfbox.contentstream.operator.state.Restore;
import org.apache.pdfbox.contentstream.operator.state.Save;
import org.apache.pdfbox.contentstream.operator.state.SetGraphicsStateParameters;
import org.apache.pdfbox.contentstream.operator.state.SetMatrix;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The per-page routing checks. Each check is something that can be measured today, without a model:
 *
 * <ol>
 *   <li><b>Text layer</b>: PDFBox text of the page (non-blank characters), as the product reads it.</li>
 *   <li><b>OCR confidence</b>: the product's own {@code TesseractOcrAdapter} (same command, language, page
 *       segmentation mode and 220 dpi rendering as the product), mean word confidence. OCR runs when the page
 *       is a scan (one image covering 85% or more) or has fewer than 32 text-layer characters.</li>
 *   <li><b>Layer and image disagree</b>: on a scan that also has a text layer, the share of text-layer
 *       words (3+ letters) that OCR also read. Below 0.5 they disagree.</li>
 *   <li><b>Pictures (photo, ID, check)</b>: the page rendered at 72 dpi, cut into 12 x 12 pixel tiles. A tile is
 *       picture-like when more than 55% of it is not white and it is either coloured (mean saturation above
 *       0.12) or mostly mid-grey (more than 45% of pixels between 60 and 200). Connected picture tiles that
 *       cover at least 1.5% of the page are a picture region.</li>
 *   <li><b>Handwriting and signatures</b>: the page rendered at 100 dpi; ink is any pixel darker than 140.
 *       Ink explained by typed text is removed (text-layer glyph boxes on digital pages; OCR words with
 *       confidence 0.6 or more on scans), and so are long straight lines (form lines, 40 px or more) and
 *       picture regions. What is left is grouped into regions (5 px cells, joined across 2 cells). A region of
 *       at least 60 ink pixels, 8 px high and 15 px wide is unexplained ink. It counts as a <b>signature</b>
 *       when a typed word containing "sign" or "initial" sits on the same row to its left or just below it,
 *       and as <b>handwriting</b> otherwise.</li>
 * </ol>
 */
public final class PageRouter {

    static final int OCR_DPI = 220;
    static final int MIN_NATIVE_CHARS = 32;
    static final double SCAN_COVERAGE = 0.85;

    static final int PICTURE_DPI = 72;
    static final int TILE = 12;
    static final int TILE_WHITE = 245;
    static final double TILE_NON_WHITE = 0.55;
    static final double TILE_SATURATION = 0.05;
    static final double TILE_MIDTONE = 0.45;
    static final double PICTURE_MIN_AREA = 0.015;

    static final int INK_DPI = 100;
    static final int INK_LEVEL = 170;
    static final double OCR_WORD_EXPLAINS = 0.6;
    static final int LINE_RUN = 40;
    static final int CELL = 5;
    static final int JOIN_CELLS = 2;
    static final int REGION_MIN_INK = 30;
    static final int REGION_MIN_HEIGHT = 8;
    static final int REGION_MIN_WIDTH = 15;

    private static final Pattern SIGN_LABEL = Pattern.compile("(?i).*(sign|initial).*");

    private final OcrPort ocr;

    public PageRouter(OcrPort ocr) {
        this.ocr = ocr;
    }

    /** A word with a normalized box (0..1, top-left). */
    record Word(String text, double left, double top, double width, double height, double confidence) {
    }

    public PageSignals analyze(PDDocument document, int index) throws IOException {
        long started = System.nanoTime();
        PDPage page = document.getPage(index);
        List<Word> layerWords = textLayerWords(document, index);
        int layerChars = layerWords.stream().mapToInt(w -> w.text().length()).sum();
        double coverage = imageCoverage(page);
        boolean scanned = coverage >= SCAN_COVERAGE;
        PDFRenderer renderer = new PDFRenderer(document);

        boolean ocrRun = scanned || layerChars < MIN_NATIVE_CHARS;
        double ocrConfidence = -1;
        double agreement = -1;
        List<Word> ocrWords = List.of();
        long ocrNanos = 0;
        if (ocrRun) {
            long ocrStart = System.nanoTime();
            BufferedImage image = renderer.renderImageWithDPI(index, OCR_DPI, ImageType.RGB);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(image, "png", png);
            OcrPort.OcrPageOutput out = ocr.recognize(new OcrPort.OcrPageInput(index + 1, png.toByteArray(), "image/png",
                    image.getWidth(), image.getHeight()));
            ocrNanos = System.nanoTime() - ocrStart;
            ocrConfidence = out.text().isBlank() ? 0.0 : out.confidence();
            ocrWords = words(out);
            if (scanned && layerChars >= MIN_NATIVE_CHARS) {
                agreement = agreement(layerWords, ocrWords);
            }
        }

        BufferedImage small = renderer.renderImageWithDPI(index, PICTURE_DPI, ImageType.RGB);
        List<double[]> pictures = pictureRegions(small);

        BufferedImage inkImage = renderer.renderImageWithDPI(index, INK_DPI, ImageType.GRAY);
        // On a scan the text layer is not what is visible; only OCR words explain ink there.
        List<Word> explaining = new ArrayList<>(scanned ? List.of() : layerWords);
        for (Word w : ocrWords) {
            if (w.confidence() >= OCR_WORD_EXPLAINS) explaining.add(w);
        }
        List<Word> labels = new ArrayList<>(scanned ? List.of() : layerWords);
        labels.addAll(ocrWords);
        List<double[]> handwriting = new ArrayList<>();
        List<double[]> signatures = new ArrayList<>();
        for (double[] region : inkRegions(inkImage, explaining, pictures)) {
            (nearSignatureLabel(region, labels) ? signatures : handwriting).add(region);
        }
        long total = System.nanoTime() - started;
        return new PageSignals(index + 1, layerChars, coverage, scanned, ocrRun, ocrConfidence, agreement,
                pictures, handwriting, signatures, ocrNanos / 1_000_000, (total - ocrNanos) / 1_000_000);
    }

    // --- text layer --------------------------------------------------------------------------------

    static List<Word> textLayerWords(PDDocument document, int index) throws IOException {
        List<Word> words = new ArrayList<>();
        PDPage page = document.getPage(index);
        float pw = page.getMediaBox().getWidth(), ph = page.getMediaBox().getHeight();
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> positions) {
                StringBuilder word = new StringBuilder();
                double l = 0, t = 0, r = 0, b = 0;
                for (TextPosition tp : positions) {
                    String u = tp.getUnicode();
                    if (u == null || u.isBlank()) {
                        if (!word.isEmpty()) words.add(new Word(word.toString(), l / pw, t / ph, (r - l) / pw, (b - t) / ph, 1));
                        word.setLength(0);
                        continue;
                    }
                    double x = tp.getXDirAdj(), y = tp.getYDirAdj() - tp.getHeightDir(), x2 = x + tp.getWidthDirAdj(), y2 = tp.getYDirAdj();
                    if (word.isEmpty()) {
                        l = x; t = y; r = x2; b = y2;
                    } else {
                        l = Math.min(l, x); t = Math.min(t, y); r = Math.max(r, x2); b = Math.max(b, y2);
                    }
                    word.append(u);
                }
                if (!word.isEmpty()) words.add(new Word(word.toString(), l / pw, t / ph, (r - l) / pw, (b - t) / ph, 1));
            }
        };
        stripper.setStartPage(index + 1);
        stripper.setEndPage(index + 1);
        stripper.getText(document);
        return words;
    }

    private static List<Word> words(OcrPort.OcrPageOutput out) {
        List<Word> words = new ArrayList<>();
        for (OcrPort.OcrTextSpan span : out.spans()) {
            words.add(new Word(out.text().substring(span.startOffset(), span.endOffset()), span.left(), span.top(),
                    span.width(), span.height(), span.confidence()));
        }
        return words;
    }

    /** How far apart (share of the page) the same word may sit in the text layer and in the OCR. */
    static final double AGREEMENT_DISTANCE = 0.03;

    /**
     * Share of text-layer words (3+ letters or digits) that OCR read as the same word at about the same
     * place on the page. A matching vocabulary in the wrong places does not count.
     */
    static double agreement(List<Word> layer, List<Word> ocr) {
        int total = 0, found = 0;
        for (Word w : layer) {
            String t = token(w.text());
            if (t.length() < 3) continue;
            total++;
            double cx = w.left() + w.width() / 2, cy = w.top() + w.height() / 2;
            for (Word o : ocr) {
                double ox = o.left() + o.width() / 2, oy = o.top() + o.height() / 2;
                if (Math.abs(ox - cx) <= AGREEMENT_DISTANCE && Math.abs(oy - cy) <= AGREEMENT_DISTANCE && token(o.text()).equals(t)) {
                    found++;
                    break;
                }
            }
        }
        return total < 10 ? -1 : (double) found / total;
    }

    private static String token(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    // --- images in the page ------------------------------------------------------------------------

    static double imageCoverage(PDPage page) throws IOException {
        float pageArea = page.getMediaBox().getWidth() * page.getMediaBox().getHeight();
        double[] area = {0};
        PDFStreamEngine engine = new PDFStreamEngine() {
            {
                addOperator(new Concatenate(this));
                addOperator(new SetGraphicsStateParameters(this));
                addOperator(new Save(this));
                addOperator(new Restore(this));
                addOperator(new SetMatrix(this));
                addOperator(new DrawObject(this));
            }

            @Override
            protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
                if ("Do".equals(operator.getName()) && !operands.isEmpty() && operands.get(0) instanceof COSName name) {
                    PDXObject xobject = getResources().getXObject(name);
                    if (xobject instanceof PDImageXObject) {
                        Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
                        area[0] += Math.abs(ctm.getScalingFactorX() * ctm.getScalingFactorY());
                        return;
                    }
                }
                super.processOperator(operator, operands);
            }
        };
        engine.processPage(page);
        return Math.min(1.0, area[0] / pageArea);
    }

    // --- pictures ----------------------------------------------------------------------------------

    static List<double[]> pictureRegions(BufferedImage image) {
        int cols = image.getWidth() / TILE, rows = image.getHeight() / TILE;
        boolean[][] picture = new boolean[rows][cols];
        for (int ty = 0; ty < rows; ty++) {
            for (int tx = 0; tx < cols; tx++) {
                int nonWhite = 0, mid = 0;
                double saturation = 0;
                for (int y = ty * TILE; y < (ty + 1) * TILE; y++) {
                    for (int x = tx * TILE; x < (tx + 1) * TILE; x++) {
                        int rgb = image.getRGB(x, y);
                        int r = rgb >> 16 & 0xff, g = rgb >> 8 & 0xff, b = rgb & 0xff;
                        int lum = (r * 2126 + g * 7152 + b * 722) / 10000;
                        if (lum < TILE_WHITE) {
                            nonWhite++;
                            int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
                            saturation += max == 0 ? 0 : (max - min) / (double) max;
                        }
                        if (lum >= 60 && lum <= 200) mid++;
                    }
                }
                int n = TILE * TILE;
                double nonWhiteShare = nonWhite / (double) n;
                double meanSat = nonWhite == 0 ? 0 : saturation / nonWhite;
                picture[ty][tx] = nonWhiteShare > TILE_NON_WHITE
                        && (meanSat > TILE_SATURATION || mid / (double) n > TILE_MIDTONE);
            }
        }
        List<double[]> regions = new ArrayList<>();
        for (int[] c : components(picture, 0)) {
            double area = (double) c[4] / (rows * cols);
            if (area >= PICTURE_MIN_AREA) {
                regions.add(new double[]{(double) c[0] / cols, (double) c[1] / rows,
                        (double) (c[2] - c[0] + 1) / cols, (double) (c[3] - c[1] + 1) / rows});
            }
        }
        return regions;
    }

    // --- unexplained ink ---------------------------------------------------------------------------

    static List<double[]> inkRegions(BufferedImage gray, List<Word> explaining, List<double[]> pictures) {
        int w = gray.getWidth(), h = gray.getHeight();
        boolean[][] ink = new boolean[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                ink[y][x] = (gray.getRGB(x, y) & 0xff) < INK_LEVEL;
            }
        }
        // long straight lines (form lines, table rules)
        boolean[][] keep = new boolean[h][w];
        for (int y = 0; y < h; y++) System.arraycopy(ink[y], 0, keep[y], 0, w);
        for (int y = 0; y < h; y++) {
            int run = 0;
            for (int x = 0; x <= w; x++) {
                if (x < w && ink[y][x]) run++;
                else {
                    if (run >= LINE_RUN) for (int k = x - run; k < x; k++) keep[y][k] = false;
                    run = 0;
                }
            }
        }
        for (int x = 0; x < w; x++) {
            int run = 0;
            for (int y = 0; y <= h; y++) {
                if (y < h && ink[y][x]) run++;
                else {
                    if (run >= LINE_RUN) for (int k = y - run; k < y; k++) keep[k][x] = false;
                    run = 0;
                }
            }
        }
        for (Word word : explaining) clear(keep, word.left(), word.top(), word.width(), word.height(), 2);
        for (double[] p : pictures) clear(keep, p[0], p[1], p[2], p[3], 3);

        int cols = (w + CELL - 1) / CELL, rows = (h + CELL - 1) / CELL;
        int[][] count = new int[rows][cols];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) if (keep[y][x]) count[y / CELL][x / CELL]++;
        boolean[][] cells = new boolean[rows][cols];
        for (int y = 0; y < rows; y++) for (int x = 0; x < cols; x++) cells[y][x] = count[y][x] >= 3;

        List<double[]> regions = new ArrayList<>();
        for (int[] c : components(cells, JOIN_CELLS)) {
            int inkPixels = 0;
            for (int y = c[1]; y <= c[3]; y++) for (int x = c[0]; x <= c[2]; x++) if (cells[y][x]) inkPixels += count[y][x];
            int pxW = (c[2] - c[0] + 1) * CELL, pxH = (c[3] - c[1] + 1) * CELL;
            if (inkPixels >= REGION_MIN_INK && pxH >= REGION_MIN_HEIGHT && pxW >= REGION_MIN_WIDTH) {
                regions.add(new double[]{(double) c[0] * CELL / w, (double) c[1] * CELL / h, (double) pxW / w, (double) pxH / h});
            }
        }
        return regions;
    }

    private static void clear(boolean[][] mask, double l, double t, double bw, double bh, int pad) {
        int h = mask.length, w = mask[0].length;
        int x0 = Math.max(0, (int) Math.floor(l * w) - pad), y0 = Math.max(0, (int) Math.floor(t * h) - pad);
        int x1 = Math.min(w - 1, (int) Math.ceil((l + bw) * w) + pad), y1 = Math.min(h - 1, (int) Math.ceil((t + bh) * h) + pad);
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) mask[y][x] = false;
    }

    static boolean nearSignatureLabel(double[] region, List<Word> labels) {
        double rl = region[0], rt = region[1], rr = region[0] + region[2], rb = region[1] + region[3];
        for (Word word : labels) {
            if (!SIGN_LABEL.matcher(word.text()).matches()) continue;
            double wcY = word.top() + word.height() / 2;
            double wr = word.left() + word.width();
            boolean sameRowLeft = wcY >= rt - region[3] && wcY <= rb + region[3] * 0.5
                    && wr <= rl + 0.02 && rl - wr < 0.35;
            boolean below = word.top() >= rb - 0.005 && word.top() - rb < 0.035
                    && word.left() < rr && wr > rl - 0.05;
            if (sameRowLeft || below) return true;
        }
        return false;
    }

    /**
     * Connected components of true cells, joining cells up to {@code join} apart.
     * Each result is {minX, minY, maxX, maxY, cellCount}.
     */
    static List<int[]> components(boolean[][] grid, int join) {
        int rows = grid.length, cols = rows == 0 ? 0 : grid[0].length;
        boolean[][] seen = new boolean[rows][cols];
        List<int[]> out = new ArrayList<>();
        int[] stack = new int[rows * cols * 2 + 2];
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                if (!grid[y][x] || seen[y][x]) continue;
                int minX = x, minY = y, maxX = x, maxY = y, n = 0, sp = 0;
                stack[sp++] = x;
                stack[sp++] = y;
                seen[y][x] = true;
                while (sp > 0) {
                    int cy = stack[--sp], cx = stack[--sp];
                    n++;
                    minX = Math.min(minX, cx); maxX = Math.max(maxX, cx); minY = Math.min(minY, cy); maxY = Math.max(maxY, cy);
                    for (int dy = -join - 1; dy <= join + 1; dy++) {
                        for (int dx = -join - 1; dx <= join + 1; dx++) {
                            if (join == 0 && Math.abs(dx) + Math.abs(dy) != 1) continue;
                            int nx = cx + dx, ny = cy + dy;
                            if (nx < 0 || ny < 0 || nx >= cols || ny >= rows || seen[ny][nx] || !grid[ny][nx]) continue;
                            seen[ny][nx] = true;
                            stack[sp++] = nx;
                            stack[sp++] = ny;
                        }
                    }
                }
                out.add(new int[]{minX, minY, maxX, maxY, n});
            }
        }
        return out;
    }
}
