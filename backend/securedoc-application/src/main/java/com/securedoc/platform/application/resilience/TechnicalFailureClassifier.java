package com.securedoc.platform.application.resilience;

import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;

/** Extracts a stable non-sensitive failure classification from a throwable chain. */
public final class TechnicalFailureClassifier {
    private TechnicalFailureClassifier() {}

    public static Classification classify(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof TechnicalDependencyException technical) {
                return new Classification(
                        technical.dependency(), technical.failureCode(), technical.retryable(), technical.attempts());
            }
            current = current.getCause();
        }
        return new Classification(TechnicalDependency.UNKNOWN,
                TechnicalFailureCode.UNKNOWN_TECHNICAL_FAILURE, false, 1);
    }

    public static String safeAuditDetail(Throwable failure, String prefix) {
        Classification classification = classify(failure);
        return prefix + ": dependency=" + classification.dependency()
                + ", code=" + classification.failureCode()
                + ", retryable=" + classification.retryable()
                + ", attempts=" + classification.attempts();
    }

    public record Classification(TechnicalDependency dependency,
                                 TechnicalFailureCode failureCode,
                                 boolean retryable,
                                 int attempts) {}
}
