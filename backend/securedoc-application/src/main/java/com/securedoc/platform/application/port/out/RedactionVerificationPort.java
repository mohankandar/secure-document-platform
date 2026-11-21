package com.securedoc.platform.application.port.out;

import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.port.out.PdfRedactionPort.RedactionTarget;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;

/**
 * Verifies the security properties expected from a finished V1 redaction artifact.
 * Implementations inspect the generated PDF; callers must not export an artifact that
 * fails verification. Native text on untouched pages is allowed, but pages containing
 * redaction targets must satisfy the permanent burn/coverage checks.
 */
public interface RedactionVerificationPort {

    /**
     * Verifies a selective permanent-redaction output against independent source expectations.
     *
     * @param pdfContent generated PDF content stream
     * @param expectedSourcePageCount page count recorded from source extraction, not from the redaction result
     * @param expectedTargets exact normalized regions that should have been permanently covered
     */
    VerificationResult verify(InputStream pdfContent,
                              int expectedSourcePageCount,
                              List<RedactionTarget> expectedTargets);

    /** Test/backward-compatible convenience overload for small in-memory fixtures. */
    default VerificationResult verify(byte[] pdfContent,
                                      int expectedSourcePageCount,
                                      List<RedactionTarget> expectedTargets) {
        return verify(pdfContent == null ? null : new ByteArrayInputStream(pdfContent),
                expectedSourcePageCount, expectedTargets);
    }

    /**
     * hasExtractableText means extractable text was found on a page that was required to be
     * burn-flattened because it contains a redaction target. Untouched-page text is permitted.
     *
     * <p>Task 0023 C3: {@code failureCode} names the exact check that failed (a {@code REDACTION_*}
     * code), so the reason reaches the incident and the audit. {@code failureReason} is a fixed,
     * content-free sentence for the same check.
     */
    record VerificationResult(
            boolean safe,
            int pageCount,
            int expectedRedactionCount,
            int coveredRedactionCount,
            boolean hasExtractableText,
            String failureReason,
            TechnicalFailureCode failureCode
    ) {
        public static VerificationResult safe(int pageCount, int expectedCount, int coveredCount) {
            return new VerificationResult(true, pageCount, expectedCount, coveredCount, false, null, null);
        }

        public static VerificationResult failed(int pageCount, int expectedCount, int coveredCount,
                                                boolean hasExtractableText, TechnicalFailureCode code,
                                                String reason) {
            return new VerificationResult(false, pageCount, expectedCount, coveredCount,
                    hasExtractableText, reason, code == null ? TechnicalFailureCode.REDACTION_OUTPUT_UNPARSABLE : code);
        }
    }
}
