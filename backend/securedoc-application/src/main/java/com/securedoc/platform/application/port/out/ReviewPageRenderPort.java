package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.finding.FindingLocation;

import java.io.InputStream;
import java.util.List;

/**
 * Provider-neutral renderer for a single PDF page used by focused Manual Review.
 * Known-sensitive findings that were already confirmed may be supplied as preview-only masks so
 * reviewers are not shown values that no longer require a human decision. These masks never alter
 * the retained original; they are applied only to the rendered preview image.
 */
public interface ReviewPageRenderPort {

    RenderedPage render(InputStream pdfContent, int pageNumber);

    default RenderedPage render(InputStream pdfContent, int pageNumber,
                                List<FindingLocation.BoundingBox> previewMasks) {
        return render(pdfContent, pageNumber);
    }

    record RenderedPage(byte[] content, String mimeType, int pixelWidth, int pixelHeight) {
        public RenderedPage {
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
