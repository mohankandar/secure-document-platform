package com.securedoc.platform.application.exception;

import com.securedoc.platform.domain.error.LvCoded;
import com.securedoc.platform.domain.error.LvErrorCode;

import java.time.Duration;
import java.util.Objects;

/**
 * Typed technical dependency failure. The message is intentionally generic; callers should
 * persist only dependency/code/retryability, never arbitrary provider exception text.
 */
public class TechnicalDependencyException extends RuntimeException implements LvCoded {

    private final TechnicalDependency dependency;
    private final TechnicalFailureCode failureCode;
    private final boolean retryable;
    private final int attempts;
    /** Task 0025: the precise LexaVault code where one internal code covers several causes (for example DELIVERY_IO). */
    private final LvErrorCode lvCode;
    /** Task 0028: the destination answered 429 ("slow down"). Read only by export jobs; behaviour is otherwise unchanged. */
    private final boolean rateLimited;
    /** Task 0028: the wait a 429 asked for (Retry-After); null when it gave none. */
    private final Duration retryAfter;

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

    /** Task 0025: for subclasses that know the precise LexaVault code when they are raised. */
    protected TechnicalDependencyException(TechnicalDependency dependency,
                                           TechnicalFailureCode failureCode,
                                           boolean retryable,
                                           String safeMessage,
                                           Throwable cause,
                                           LvErrorCode lvCode) {
        this(dependency, failureCode, retryable, safeMessage, cause, 1, lvCode, false, null);
    }

    private TechnicalDependencyException(TechnicalDependency dependency,
                                         TechnicalFailureCode failureCode,
                                         boolean retryable,
                                         String safeMessage,
                                         Throwable cause,
                                         int attempts) {
        this(dependency, failureCode, retryable, safeMessage, cause, attempts, null, false, null);
    }

    private TechnicalDependencyException(TechnicalDependency dependency,
                                         TechnicalFailureCode failureCode,
                                         boolean retryable,
                                         String safeMessage,
                                         Throwable cause,
                                         int attempts,
                                         LvErrorCode lvCode,
                                         boolean rateLimited,
                                         Duration retryAfter) {
        super(safeMessage, cause);
        this.lvCode = lvCode;
        this.dependency = Objects.requireNonNull(dependency);
        this.failureCode = Objects.requireNonNull(failureCode);
        this.retryable = retryable;
        this.attempts = Math.max(1, attempts);
        this.rateLimited = rateLimited;
        this.retryAfter = retryAfter;
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
                dependency, failureCode, retryable, getMessage(), this, attemptCount, lvCode, rateLimited, retryAfter);
    }

    /**
     * Task 0025: the same failure with its precise LexaVault code, for an internal code that covers several causes
     * (for example DELIVERY_IO for "can't be reached", "busy" and "answer not understood"). Behaviour is unchanged.
     */
    public TechnicalDependencyException withLvCode(LvErrorCode code) {
        return new TechnicalDependencyException(dependency, failureCode, retryable, getMessage(), getCause(), attempts,
                code, rateLimited, retryAfter);
    }

    /**
     * Task 0028: the same failure marked as a 429 ("slow down"), with the wait it asked for (null when none). Only
     * export jobs read the mark; the code, retry and message are unchanged.
     */
    public TechnicalDependencyException asRateLimited(Duration wait) {
        Duration safeWait = wait == null || wait.isNegative() ? null : wait;
        return new TechnicalDependencyException(dependency, failureCode, retryable, getMessage(), getCause(), attempts,
                lvCode, true, safeWait);
    }

    /** The precise LexaVault code, or {@code null}: then the code follows from {@link #failureCode()}. */
    @Override
    public LvErrorCode lvCode() {
        return lvCode;
    }

    /** Task 0028: true when the destination answered 429. */
    public boolean rateLimited() {
        return rateLimited;
    }

    /** Task 0028: the wait a 429 asked for (Retry-After), or null. */
    public Duration retryAfter() {
        return retryAfter;
    }
}
