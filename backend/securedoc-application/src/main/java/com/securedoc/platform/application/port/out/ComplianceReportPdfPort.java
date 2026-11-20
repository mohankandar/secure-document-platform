package com.securedoc.platform.application.port.out;

import com.securedoc.platform.application.compliance.ComplianceReportContent;

/**
 * Lays out the compliance report as a PDF (task 0017 B3). Built in memory and returned as bytes; it is never put in
 * storage. Fonts are embedded from the application's own resources; nothing is loaded from the internet.
 */
public interface ComplianceReportPdfPort {

    /** The PDF; {@code fingerprint} is the content fingerprint printed on the cover and in every footer. */
    byte[] render(ComplianceReportContent content, String fingerprint);
}
