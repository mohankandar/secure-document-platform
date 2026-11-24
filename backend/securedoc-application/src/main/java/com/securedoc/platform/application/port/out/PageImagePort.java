package com.securedoc.platform.application.port.out;

import java.io.IOException;
import java.io.InputStream;

/** Task 0064: a page of a PDF as the image the vision model sees. In memory only. */
public interface PageImagePort {

    /** @param longSidePixels the image's longer side */
    PageImage render(InputStream pdf, int page, int longSidePixels) throws IOException;

    record PageImage(byte[] png, int width, int height) {
        /** No content. */
        @Override
        public String toString() {
            return "PageImage[" + width + "x" + height + "]";
        }
    }
}
