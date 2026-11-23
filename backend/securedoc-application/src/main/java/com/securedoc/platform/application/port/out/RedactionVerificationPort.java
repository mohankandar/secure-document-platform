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
     * Task 0044: the final check with the output options and the values that were redacted. With a searchable text
     * layer, redacted pages may hold (OCR) text; in every mode, no redacted value may appear in the text of any output
     * page (failure {@link TechnicalFailureCode#REDACTION_VALUE_IN_OUTPUT_TEXT}). The comparison is in memory only.
     * An implementation that cannot run that comparison must refuse rather than pass.
     */
    default VerificationResult verify(InputStream pdfContent,
                                      int expectedSourcePageCount,
                                      List<RedactionTarget> expectedTargets,
                                      boolean searchableText,
                                      RedactedValues redactedValues) {
        if (searchableText || (redactedValues != null && !redactedValues.isEmpty())) {
            throw new UnsupportedOperationException("This verifier cannot check the output text for redacted values");
        }
        return verify(pdfContent, expectedSourcePageCount, expectedTargets);
    }

    /**
     * The values that were redacted, for the final check only. Held in memory for one verification and never saved,
     * logged or printed: {@link #toString()} gives the count only and there is no getter for the raw values.
     * Each value is kept in its compared form (letters and digits only, lower case); values shorter than
     * {@link #MIN_CHARACTERS} are left out because they cannot be told apart from other numbers on a page.
     */
    final class RedactedValues {
        public static final int MIN_CHARACTERS = 4;
        private static final RedactedValues NONE = new RedactedValues(List.of());

        private final List<String> compared;

        private RedactedValues(List<String> compared) {
            this.compared = List.copyOf(compared);
        }

        public static RedactedValues none() {
            return NONE;
        }

        /** Raw values as they appear on the page; each becomes its compared form, duplicates and short ones dropped. */
        public static RedactedValues of(java.util.Collection<String> rawValues) {
            java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
            if (rawValues != null) {
                for (String raw : rawValues) {
                    String c = comparable(raw);
                    if (c.length() >= MIN_CHARACTERS) out.add(c);
                }
            }
            return out.isEmpty() ? NONE : new RedactedValues(new java.util.ArrayList<>(out));
        }

        /** Letters and digits only, lower case: "412-55-7531" and "412 55 7531" compare the same. */
        public static String comparable(String raw) {
            if (raw == null) return "";
            StringBuilder b = new StringBuilder(raw.length());
            raw.codePoints().filter(Character::isLetterOrDigit).map(Character::toLowerCase).forEach(b::appendCodePoint);
            return b.toString();
        }

        public boolean isEmpty() {
            return compared.isEmpty();
        }

        public int size() {
            return compared.size();
        }

        /**
         * True when a redacted value appears in this page text: a run of consecutive words, starting at a word, that
         * compares equal to it, or, for values of 8 or more characters, one word that contains it.
         */
        public boolean appearsIn(String pageText) {
            if (compared.isEmpty() || pageText == null || pageText.isBlank()) return false;
            List<String> words = new java.util.ArrayList<>();
            for (String w : pageText.split("\\s+")) {
                String c = comparable(w);
                if (!c.isEmpty()) words.add(c);
            }
            for (String value : compared) {
                for (int i = 0; i < words.size(); i++) {
                    if (value.length() >= 8 && words.get(i).contains(value)) return true;
                    StringBuilder run = new StringBuilder();
                    for (int j = i; j < words.size() && run.length() < value.length(); j++) {
                        run.append(words.get(j));
                        if (run.length() == value.length() && run.toString().equals(value)) return true;
                    }
                }
            }
            return false;
        }

        @Override
        public String toString() {
            return "RedactedValues[count=" + compared.size() + "]";
        }
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
