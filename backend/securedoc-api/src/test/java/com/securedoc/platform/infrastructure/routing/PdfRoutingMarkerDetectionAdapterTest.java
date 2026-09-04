package com.securedoc.platform.infrastructure.routing;

import com.securedoc.platform.application.port.out.OcrPort;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.routing.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PdfRoutingMarkerDetectionAdapterTest {
    @Test
    void textMarkerIsDetectedOnlyFromConfiguredTopRightCrop() throws Exception {
        byte[] pdf = pdfWithTopRightBlackMarker();
        OcrPort markerAwareOcr = input -> {
            try {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(input.imageBytes()));
                long dark = 0;
                for (int y = 0; y < image.getHeight(); y += 4) {
                    for (int x = 0; x < image.getWidth(); x += 4) {
                        int rgb = image.getRGB(x, y);
                        int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
                        if (r + g + b < 300) dark++;
                    }
                }
                return new OcrPort.OcrPageOutput(dark > 30 ? "||||77777777||||" : "", List.of(), 1.0);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
        var adapter = new PdfRoutingMarkerDetectionAdapter(markerAwareOcr);
        TenantId tenant = TenantId.generate();
        IntegrationConnectionId destination = IntegrationConnectionId.generate();
        RoutingRule topRight = RoutingRule.create(tenant, "SC", RoutingMarkerType.TEXT_PATTERN, "77777777",
                RoutingScanRegion.TOP_RIGHT, destination, true, Instant.now());
        RoutingRule bottomLeft = RoutingRule.create(tenant, "SC2", RoutingMarkerType.TEXT_PATTERN, "77777777",
                RoutingScanRegion.BOTTOM_LEFT, destination, true, Instant.now());

        var result = adapter.detect(new ByteArrayInputStream(pdf), List.of(topRight, bottomLeft));
        assertEquals(List.of(1), result.pagesByRule().get(topRight.id()));
        assertFalse(result.pagesByRule().containsKey(bottomLeft.id()));
        assertTrue(result.ambiguousPages().isEmpty());
    }

    @Test
    void conflictingDestinationsOnSamePageAreAmbiguousAndNotRouted() throws Exception {
        byte[] pdf = pdfWithTopRightBlackMarker();
        OcrPort alwaysMarker = input -> new OcrPort.OcrPageOutput("77777777 88888888", List.of(), 1.0);
        var adapter = new PdfRoutingMarkerDetectionAdapter(alwaysMarker);
        TenantId tenant = TenantId.generate();
        RoutingRule a = RoutingRule.create(tenant, "SC", RoutingMarkerType.TEXT_PATTERN, "77777777",
                RoutingScanRegion.TOP_RIGHT, IntegrationConnectionId.generate(), true, Instant.now());
        RoutingRule b = RoutingRule.create(tenant, "ATL", RoutingMarkerType.TEXT_PATTERN, "88888888",
                RoutingScanRegion.TOP_RIGHT, IntegrationConnectionId.generate(), true, Instant.now());

        var result = adapter.detect(new ByteArrayInputStream(pdf), List.of(a, b));
        assertEquals(java.util.Set.of(1), result.ambiguousPages());
        assertTrue(result.pagesByRule().isEmpty());
    }

    private byte[] pdfWithTopRightBlackMarker() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.setNonStrokingColor(0, 0, 0);
                float w = page.getMediaBox().getWidth();
                float h = page.getMediaBox().getHeight();
                content.addRect(w * 0.72f, h * 0.82f, w * 0.22f, h * 0.12f);
                content.fill();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
