package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.finding.FindingLocation.BoundingBox;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Task 0064: a page of a PDF as the image the vision model sees. In memory only. */
public interface PageImagePort {

    /** @param longSidePixels the image's longer side */
    PageImage render(InputStream pdf, int page, int longSidePixels) throws IOException;

    /**
     * Task 0092: the machine-readable lines of checks on the page, found by their shape. Places only: nothing is read. None
     * where this is not supported.
     */
    default List<CheckLine> checkLines(InputStream pdf, int page) throws IOException {
        return List.of();
    }

    record CheckLine(BoundingBox routing, BoundingBox account) { }

    record PageImage(byte[] png, int width, int height) {
        /** No content. */
        @Override
        public String toString() {
            return "PageImage[" + width + "x" + height + "]";
        }
    }
}
