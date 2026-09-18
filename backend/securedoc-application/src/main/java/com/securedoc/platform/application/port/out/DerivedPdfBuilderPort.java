package com.securedoc.platform.application.port.out;

import java.io.InputStream;
import java.util.List;

/** Builds a new PDF from already-redacted source pages while preserving original page order. */
public interface DerivedPdfBuilderPort {
    ReplayableContent build(InputStream redactedPdf, List<Integer> pageNumbers);
}
