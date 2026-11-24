package com.securedoc.platform.domain.vision;

import com.securedoc.platform.domain.finding.FindingLocation.BoundingBox;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Task 0064: how one page is read, and why (docs/work/vision-routing-requirement.md, section 2). Numbers, names and
 * boxes only: never text from the page.
 *
 * @param ocrConfidence mean OCR word confidence (0 to 1); null when the routing checks did not need OCR
 * @param signatures    places the signature detector found (used on {@link Path#FAST_SIGNATURE} pages, and on a page
 *                      the vision model could not read)
 */
public record PageRoute(int page, Path path, Set<Reason> reasons, Double ocrConfidence, List<BoundingBox> signatures) {

    /** The names are stored; never rename one. */
    public enum Path { FAST, FAST_SIGNATURE, VISION }

    /** The names are stored; never rename one. */
    public enum Reason {
        PICTURE, LAYER_DISAGREES, LOW_OCR_CONFIDENCE, HANDWRITING, SIGNATURE_ONLY,
        /** The text reader could not read the page at all. */
        NOT_READABLE,
        /** A routing check failed on this page: it is treated as hard (fail closed). */
        CHECK_FAILED
    }

    public PageRoute {
        if (page < 1) throw new IllegalArgumentException("Page must be >= 1");
        Objects.requireNonNull(path, "path");
        EnumSet<Reason> ordered = EnumSet.noneOf(Reason.class);
        ordered.addAll(reasons);
        reasons = Collections.unmodifiableSet(ordered);
        if (ocrConfidence != null && !(ocrConfidence >= 0 && ocrConfidence <= 1)) {
            throw new IllegalArgumentException("OCR confidence must be between 0 and 1");
        }
        signatures = List.copyOf(signatures);
    }

    public boolean vision() {
        return path == Path.VISION;
    }
}
