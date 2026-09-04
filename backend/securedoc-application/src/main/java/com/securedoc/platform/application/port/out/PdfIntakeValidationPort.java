package com.securedoc.platform.application.port.out;

import java.io.InputStream;

/** Lightweight PDF structural validation used before machine-intake acknowledgement. */
public interface PdfIntakeValidationPort {
    PdfInfo inspect(InputStream content);

    record PdfInfo(int pageCount) {
        public PdfInfo {
            if (pageCount < 1) throw new IllegalArgumentException("PDF must contain at least one page");
        }
    }
}
