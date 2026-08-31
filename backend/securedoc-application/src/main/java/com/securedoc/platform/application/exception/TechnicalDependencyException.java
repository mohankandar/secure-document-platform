package com.securedoc.platform.application.exception;

import java.util.Objects;

/**
 * Typed technical dependency failure. The message is intentionally generic; callers should
 * persist only dependency/code/retryability, never arbitrary provider exception text.
 */
public class TechnicalDependencyException extends RuntimeException {

    private final TechnicalDependency dependency;
    private final TechnicalFailureCode failureCode;
    private final boolean retryable;
    private final int attempts;

    public TechnicalDependencyException(TechnicalDependency dependency,
                                        TechnicalFailureCode failureCode,
                                        boolean retryable,
                                        String safeMessage) {
        this(dependency, failureCode, retryable, safeMessage, null, 1);
    }

    public TechnicalDependencyException(TechnicalDependency dependency,
                                        TechnicalFailureCode failureCode,
                                        boolean retryable,
                                        String safeMessage,
                                        Throwable cause) {
        this(dependency, failureCode, retryable, safeMessage, cause, 1);
    }

    private TechnicalDependencyException(TechnicalDependency dependency,
                                         TechnicalFailureCode failureCode,
                                         boolean retryable,
                                         String safeMessage,
                                         Throwable cause,
                                         int attempts) {
        super(safeMessage, cause);
        this.dependency = Objects.requireNonNull(dependency);
        this.failureCode = Objects.requireNonNull(failureCode);
        this.retryable = retryable;
        this.attempts = Math.max(1, attempts);
    }

    public TechnicalDependency dependency() {
        return dependency;
    }

    public TechnicalFailureCode failureCode() {
        return failureCode;
    }

    public boolean retryable() {
        return retryable;
    }

    public int attempts() {
        return attempts;
    }

    public TechnicalDependencyException withAttempts(int attemptCount) {
        return new TechnicalDependencyException(
                dependency, failureCode, retryable, getMessage(), this, attemptCount);
    }
}
