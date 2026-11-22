package com.securedoc.platform.application.resilience;

import com.securedoc.platform.application.error.LvErrorCodes;
import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.domain.error.LvErrorCode;

/** Extracts a stable non-sensitive failure classification from a throwable chain. */
public final class TechnicalFailureClassifier {
    private TechnicalFailureClassifier() {}

    public static Classification classify(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof TechnicalDependencyException technical) {
                LvErrorCode lvCode = technical.lvCode() != null ? technical.lvCode()
                        : LvErrorCodes.forTechnicalFailure(technical.failureCode());
                return new Classification(technical.dependency(), technical.failureCode(), technical.retryable(),
                        technical.attempts(), lvCode);
            }
            current = current.getCause();
        }
        // Task 0025: an unclassified failure keeps UNKNOWN_TECHNICAL_FAILURE as its detail; a code the failure carries
        // (for example a refused state rule) is kept, otherwise it is LV-SYS-001.
        return new Classification(TechnicalDependency.UNKNOWN, TechnicalFailureCode.UNKNOWN_TECHNICAL_FAILURE, false, 1,
                LvErrorCodes.optional(failure).orElse(LvErrorCode.SYS_001));
    }

    /**
     * Task 0039: the 1-based page the failure happened on, from the same failure {@link #classify} reads; empty when
     * the failure is not about one page. Only a whole number from 1 is returned.
     */
    public static java.util.Optional<Integer> page(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof TechnicalDependencyException technical) {
                return java.util.Optional.ofNullable(technical.page()).filter(p -> p >= 1);
            }
            current = current.getCause();
        }
        return java.util.Optional.empty();
    }

    /**
     * The audit detail for a stop (task 0025): {@code "<prefix>: code=LV-…, detail=<TechnicalFailureCode>,
     * dependency=…, retryable=…, attempts=…"}. Codes only, never exception text. {@code TechnicalFailureAuditParser}
     * reads this form and the earlier one without {@code code=LV-…}.
     */
    public static String safeAuditDetail(Throwable failure, String prefix) {
        Classification classification = classify(failure);
        return prefix + ": code=" + classification.lvCode().code()
                + ", detail=" + classification.failureCode()
                + ", dependency=" + classification.dependency()
                + ", retryable=" + classification.retryable()
                + ", attempts=" + classification.attempts();
    }

    public record Classification(TechnicalDependency dependency,
                                 TechnicalFailureCode failureCode,
                                 boolean retryable,
                                 int attempts,
                                 LvErrorCode lvCode) {

        public Classification(TechnicalDependency dependency, TechnicalFailureCode failureCode, boolean retryable,
                              int attempts) {
            this(dependency, failureCode, retryable, attempts, LvErrorCodes.forTechnicalFailure(failureCode));
        }
    }
}
