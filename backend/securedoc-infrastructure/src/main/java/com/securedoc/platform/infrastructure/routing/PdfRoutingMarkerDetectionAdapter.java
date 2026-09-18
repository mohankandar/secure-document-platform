package com.securedoc.platform.infrastructure.routing;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import com.securedoc.platform.application.port.out.OcrPort;
import com.securedoc.platform.application.port.out.RoutingMarkerDetectionPort;
import com.securedoc.platform.application.port.out.RoutingMarkerDetectionResult;
import com.securedoc.platform.domain.routing.RoutingMarkerType;
import com.securedoc.platform.domain.routing.RoutingRule;
import com.securedoc.platform.domain.routing.RoutingScanRegion;
import com.securedoc.platform.infrastructure.pdf.PdfBoxTempFileSupport;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic V1 marker scanner.
 *
 * <p>R2A keeps the same fixed visual scan regions while avoiding expensive page rendering/OCR
 * when a TEXT_PATTERN can be evaluated from embedded native text already present in that region.
 * Barcode and QR rules still use the rendered crop. TEXT_PATTERN rules fall back to the legacy
 * rendered-crop OCR path whenever the configured region has no usable embedded text, preserving
 * scanned/raster-page behavior.</p>
 *
 * <p>Document text is never logged or persisted by this adapter.</p>
 */
@Component
public class PdfRoutingMarkerDetectionAdapter implements RoutingMarkerDetectionPort {
    private static final float DPI = 160f;
    private static final double REGION_WIDTH = 0.40d;
    private static final double REGION_HEIGHT = 0.25d;

    private final OcrPort ocr;

    public PdfRoutingMarkerDetectionAdapter(OcrPort ocr) {
        this.ocr = Objects.requireNonNull(ocr);
    }

    @Override
    public RoutingMarkerDetectionResult detect(InputStream redactedPdf, List<RoutingRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return new RoutingMarkerDetectionResult(Map.of(), Set.of());
        }

        try (PdfBoxTempFileSupport.StagedPdf staged = PdfBoxTempFileSupport.stage(redactedPdf, "lexavault-routing-")) {
            PDDocument doc = staged.document();
            PDFRenderer renderer = new PDFRenderer(doc);
            Map<RoutingScanRegion, List<RoutingRule>> rulesByRegion = rulesByRegion(rules);
            Set<RoutingScanRegion> textRegions = new LinkedHashSet<>();
            rulesByRegion.forEach((region, regionRules) -> {
                if (needsText(regionRules)) {
                    textRegions.add(region);
                }
            });

            Map<UUID, LinkedHashSet<Integer>> pages = new LinkedHashMap<>();
            Set<Integer> ambiguous = new LinkedHashSet<>();

            for (int pageIndex = 0; pageIndex < doc.getNumberOfPages(); pageIndex++) {
                int pageNumber = pageIndex + 1;
                Map<RoutingScanRegion, String> nativeTextByRegion = safeExtractNativeRegionText(
                        doc, pageIndex, textRegions);

                boolean renderRequired = rulesByRegion.entrySet().stream()
                        .anyMatch(entry -> requiresRenderedCrop(entry.getValue(), nativeTextByRegion.get(entry.getKey())));

                BufferedImage rendered = renderRequired
                        ? renderer.renderImageWithDPI(pageIndex, DPI, ImageType.RGB)
                        : null;
                try {
                    Set<RoutingRule> matched = new LinkedHashSet<>();
                    for (Map.Entry<RoutingScanRegion, List<RoutingRule>> entry : rulesByRegion.entrySet()) {
                        RoutingScanRegion region = entry.getKey();
                        List<RoutingRule> regionRules = entry.getValue();
                        String nativeText = nativeTextByRegion.getOrDefault(region, "");
                        boolean nativeTextUsable = hasUsableNativeText(nativeText);

                        boolean needsCodes = needsCodes(regionRules);
                        boolean needsOcr = needsText(regionRules) && !nativeTextUsable;
                        BufferedImage crop = (needsCodes || needsOcr) ? crop(rendered, region) : null;

                        Set<String> decoded = needsCodes ? decode(crop) : Set.of();
                        String text = nativeTextUsable
                                ? nativeText
                                : (needsOcr ? ocrText(pageNumber, crop) : "");
                        String normalizedText = normalize(text);

                        for (RoutingRule rule : regionRules) {
                            boolean hit = switch (rule.markerType()) {
                                case QR_CODE -> decoded.stream()
                                        .anyMatch(v -> v.startsWith("QR:") && v.substring(3).equals(rule.markerValue()));
                                case BARCODE -> decoded.stream()
                                        .anyMatch(v -> v.startsWith("BAR:") && v.substring(4).equals(rule.markerValue()));
                                case TEXT_PATTERN -> !normalize(rule.markerValue()).isBlank()
                                        && normalizedText.contains(normalize(rule.markerValue()));
                            };
                            if (hit) {
                                matched.add(rule);
                            }
                        }
                    }

                    long destinations = matched.stream()
                            .map(r -> r.destinationConnectionId().value())
                            .distinct()
                            .count();
                    if (destinations > 1) {
                        ambiguous.add(pageNumber);
                        continue;
                    }
                    for (RoutingRule rule : matched) {
                        pages.computeIfAbsent(rule.id(), ignored -> new LinkedHashSet<>()).add(pageNumber);
                    }
                } finally {
                    if (rendered != null) {
                        rendered.flush();
                    }
                }
            }

            Map<UUID, List<Integer>> immutable = new LinkedHashMap<>();
            pages.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
            return new RoutingMarkerDetectionResult(immutable, ambiguous);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan redacted PDF routing markers", e);
        }
    }

    private Map<RoutingScanRegion, List<RoutingRule>> rulesByRegion(List<RoutingRule> rules) {
        Map<RoutingScanRegion, List<RoutingRule>> grouped = new LinkedHashMap<>();
        for (RoutingScanRegion region : regionsNeeded(rules)) {
            grouped.put(region, rules.stream().filter(rule -> applies(rule.scanRegion(), region)).toList());
        }
        return grouped;
    }

    private Set<RoutingScanRegion> regionsNeeded(List<RoutingRule> rules) {
        LinkedHashSet<RoutingScanRegion> out = new LinkedHashSet<>();
        for (RoutingRule rule : rules) {
            if (rule.scanRegion() == RoutingScanRegion.BOTH) {
                out.add(RoutingScanRegion.TOP_RIGHT);
                out.add(RoutingScanRegion.BOTTOM_LEFT);
            } else {
                out.add(rule.scanRegion());
            }
        }
        return out;
    }

    private boolean applies(RoutingScanRegion configured, RoutingScanRegion actual) {
        return configured == RoutingScanRegion.BOTH || configured == actual;
    }

    private boolean needsText(List<RoutingRule> rules) {
        return rules.stream().anyMatch(rule -> rule.markerType() == RoutingMarkerType.TEXT_PATTERN);
    }

    private boolean needsCodes(List<RoutingRule> rules) {
        return rules.stream().anyMatch(rule -> rule.markerType() == RoutingMarkerType.BARCODE
                || rule.markerType() == RoutingMarkerType.QR_CODE);
    }

    private boolean requiresRenderedCrop(List<RoutingRule> rules, String nativeRegionText) {
        return needsCodes(rules) || (needsText(rules) && !hasUsableNativeText(nativeRegionText));
    }

    private boolean hasUsableNativeText(String value) {
        return !normalize(value).isBlank();
    }

    private Map<RoutingScanRegion, String> safeExtractNativeRegionText(
            PDDocument document,
            int pageIndex,
            Set<RoutingScanRegion> requestedRegions) {
        try {
            return extractNativeRegionText(document, pageIndex, requestedRegions);
        } catch (IOException ignored) {
            // Native text is an optimization only. If extraction is unavailable for a page,
            // preserve the legacy deterministic behavior by rendering/OCRing the crop.
            return Map.of();
        }
    }

    private Map<RoutingScanRegion, String> extractNativeRegionText(
            PDDocument document,
            int pageIndex,
            Set<RoutingScanRegion> requestedRegions) throws IOException {
        if (requestedRegions.isEmpty()) {
            return Map.of();
        }

        PDPage page = document.getPage(pageIndex);
        PDRectangle mediaBox = page.getMediaBox();
        RegionTextStripper stripper = new RegionTextStripper(
                mediaBox.getWidth(),
                mediaBox.getHeight(),
                requestedRegions);
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        stripper.setSortByPosition(true);
        stripper.getText(document);
        return stripper.result();
    }

    private BufferedImage crop(BufferedImage image, RoutingScanRegion region) {
        if (image == null) {
            throw new IllegalStateException("Rendered routing page is required for this marker rule");
        }
        int width = image.getWidth();
        int height = image.getHeight();
        int cropWidth = Math.max(1, (int) Math.round(width * REGION_WIDTH));
        int cropHeight = Math.max(1, (int) Math.round(height * REGION_HEIGHT));
        return region == RoutingScanRegion.TOP_RIGHT
                ? image.getSubimage(width - cropWidth, 0, cropWidth, cropHeight)
                : image.getSubimage(0, height - cropHeight, cropWidth, cropHeight);
    }

    private String ocrText(int page, BufferedImage crop) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(crop, "png", out);
            return ocr.recognize(new OcrPort.OcrPageInput(
                    page,
                    out.toByteArray(),
                    "image/png",
                    crop.getWidth(),
                    crop.getHeight())).text();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Set<String> decode(BufferedImage crop) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        try {
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(crop)));
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            MultiFormatReader delegate = new MultiFormatReader();
            delegate.setHints(hints);
            Result[] results = new GenericMultipleBarcodeReader(delegate).decodeMultiple(bitmap, hints);
            if (results != null) {
                for (Result result : results) {
                    if (result == null || result.getText() == null) {
                        continue;
                    }
                    values.add((result.getBarcodeFormat() == BarcodeFormat.QR_CODE ? "QR:" : "BAR:")
                            + result.getText().trim());
                }
            }
        } catch (com.google.zxing.NotFoundException ignored) {
            // No marker in this configured crop.
        }
        return values;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    /**
     * Captures only embedded text whose glyph center falls inside the same normalized regions used
     * by the legacy rendered-image crops. PDFBox adjusted text coordinates use a top-left origin.
     */
    private static final class RegionTextStripper extends PDFTextStripper {
        private final float pageWidth;
        private final float pageHeight;
        private final Set<RoutingScanRegion> requestedRegions;
        private final Map<RoutingScanRegion, StringBuilder> captured = new EnumMap<>(RoutingScanRegion.class);

        private RegionTextStripper(
                float pageWidth,
                float pageHeight,
                Set<RoutingScanRegion> requestedRegions) throws IOException {
            this.pageWidth = pageWidth;
            this.pageHeight = pageHeight;
            this.requestedRegions = requestedRegions.isEmpty()
                    ? EnumSet.noneOf(RoutingScanRegion.class)
                    : EnumSet.copyOf(requestedRegions);
            for (RoutingScanRegion region : this.requestedRegions) {
                captured.put(region, new StringBuilder());
            }
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
            if (pageWidth <= 0 || pageHeight <= 0) {
                return;
            }
            for (TextPosition position : textPositions) {
                String unicode = position.getUnicode();
                if (unicode == null || unicode.isEmpty()) {
                    continue;
                }

                double centerX = (position.getXDirAdj() + (position.getWidthDirAdj() / 2.0d)) / pageWidth;
                double centerY = (position.getYDirAdj() - (position.getHeightDir() / 2.0d)) / pageHeight;

                if (requestedRegions.contains(RoutingScanRegion.TOP_RIGHT)
                        && centerX >= (1.0d - REGION_WIDTH)
                        && centerY <= REGION_HEIGHT) {
                    captured.get(RoutingScanRegion.TOP_RIGHT).append(unicode);
                }
                if (requestedRegions.contains(RoutingScanRegion.BOTTOM_LEFT)
                        && centerX <= REGION_WIDTH
                        && centerY >= (1.0d - REGION_HEIGHT)) {
                    captured.get(RoutingScanRegion.BOTTOM_LEFT).append(unicode);
                }
            }
        }

        private Map<RoutingScanRegion, String> result() {
            Map<RoutingScanRegion, String> result = new EnumMap<>(RoutingScanRegion.class);
            captured.forEach((region, text) -> result.put(region, text.toString()));
            return result;
        }
    }
}
