package com.securedoc.platform.application.port.out;

import com.securedoc.platform.application.port.out.PdfRedactionPort.RedactionTarget;

import java.util.List;

/**
 * Verifies the security properties expected from a finished V1 redaction artifact.
 * Implementations inspect the generated PDF; callers must not export an artifact that
 * fails verification.
 */
public interface RedactionVerificationPort {

    /**
     * Verifies a burn-flat output against independent source expectations.
     *
     * @param pdfContent generated PDF bytes
     * @param expectedSourcePageCount page count recorded from source extraction, not from the redaction result
     * @param expectedTargets exact normalized regions that should have been permanently covered
     */
    VerificationResult verify(byte[] pdfContent,
                              int expectedSourcePageCount,
                              List<RedactionTarget> expectedTargets);

    record VerificationResult(
            boolean safe,
            int pageCount,
            int expectedRedactionCount,
            int coveredRedactionCount,
            boolean hasExtractableText,
            String failureReason
    ) {
        public static VerificationResult safe(int pageCount, int expectedCount, int coveredCount) {
            return new VerificationResult(true, pageCount, expectedCount, coveredCount, false, null);
        }

        public static VerificationResult failed(int pageCount, int expectedCount, int coveredCount,
                                                boolean hasExtractableText, String reason) {
            return new VerificationResult(false, pageCount, expectedCount, coveredCount,
                    hasExtractableText, reason);
        }
    }
}
