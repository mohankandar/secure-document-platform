package com.securedoc.platform.application.port.out;

import java.io.InputStream;

/**
 * Provider-neutral renderer for a single PDF page used by focused Manual Review.
 */
public interface ReviewPageRenderPort {

    RenderedPage render(InputStream pdfContent, int pageNumber);

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
