package com.securedoc.benchmark.routing;

import java.util.ArrayList;
import java.util.List;

/**
 * What the routing checks measured on one page. Numbers and boxes only: no text from the page.
 *
 * @param textLayerChars      non-blank characters in the PDF text layer (visible or not)
 * @param imageCoverage       share of the page covered by embedded images (0..1)
 * @param scanned             the page is one large image (coverage at least 0.85)
 * @param ocrRun              OCR ran for routing (scanned page or too little text layer)
 * @param ocrConfidence       mean Tesseract word confidence (0..1), or -1 when OCR did not run
 * @param layerAgreement      share of text-layer words also read by OCR, or -1 when not checked
 * @param pictureRegions      photo / ID / check-like regions found
 * @param handwritingRegions  unexplained ink regions with no signature label nearby
 * @param signatureRegions    unexplained ink regions next to a signature or initials label
 * @param ocrMillis           time spent in OCR for routing
 * @param checkMillis         time spent in the other checks (rendering and pixel analysis)
 */
public record PageSignals(int page,
                          int textLayerChars,
                          double imageCoverage,
                          boolean scanned,
                          boolean ocrRun,
                          double ocrConfidence,
                          double layerAgreement,
                          List<double[]> pictureRegions,
                          List<double[]> handwritingRegions,
                          List<double[]> signatureRegions,
                          long ocrMillis,
                          long checkMillis) {

    /** Below this share of text-layer words found by OCR, layer and image disagree. */
    public static final double LAYER_AGREEMENT_MIN = 0.5;

    public boolean layerDisagrees() {
        return layerAgreement >= 0 && layerAgreement < LAYER_AGREEMENT_MIN;
    }

    /** The routing rule of vision-routing-requirement.md section 2, at one OCR-confidence threshold. */
    public Decision route(double ocrThreshold) {
        List<String> reasons = new ArrayList<>();
        if (!pictureRegions.isEmpty()) reasons.add("PICTURE");
        if (layerDisagrees()) reasons.add("LAYER_DISAGREES");
        if (ocrRun && ocrConfidence >= 0 && ocrConfidence < ocrThreshold) reasons.add("LOW_OCR_CONFIDENCE");
        if (!handwritingRegions.isEmpty()) reasons.add("HANDWRITING");
        if (!reasons.isEmpty()) return new Decision("VISION", reasons);
        if (!signatureRegions.isEmpty()) return new Decision("FAST_SIGNATURE", List.of("SIGNATURE_ONLY"));
        return new Decision("FAST", List.of());
    }

    public record Decision(String route, List<String> reasons) {
    }
}
