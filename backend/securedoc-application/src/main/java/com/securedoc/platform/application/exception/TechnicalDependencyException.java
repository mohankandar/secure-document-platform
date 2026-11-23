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
    /**
     * Task 0030: the destination's own error code from its answer (for example {@code SDV-SEN-006}), or null. Only a
     * code that matches {@link #SAFE_DESTINATION_CODE} is kept; anything else is dropped, never stored or shown.
     */
    private final String destinationCode;
    /**
     * Task 0039: the 1-based page a text-reading (OCR) failure happened on, or null. Only the number is kept: never
     * text, an image, a file name or the provider's output.
     */
    private final Integer page;

    /** Task 0030: letters, digits, '-' and '_', 1 to 40 characters. */
    public static final java.util.regex.Pattern SAFE_DESTINATION_CODE = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,40}");

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
        this(dependency, failureCode, retryable, safeMessage, cause, 1, lvCode, false, null, null, null);
    }

    /** Task 0039: as above, for a failure on one page (1-based; a number below 1 is dropped). */
    protected TechnicalDependencyException(TechnicalDependency dependency,
                                           TechnicalFailureCode failureCode,
                                           boolean retryable,
                                           String safeMessage,
                                           Throwable cause,
                                           LvErrorCode lvCode,
                                           Integer page) {
        this(dependency, failureCode, retryable, safeMessage, cause, 1, lvCode, false, null, null, page);
    }

    private TechnicalDependencyException(TechnicalDependency dependency,
                                         TechnicalFailureCode failureCode,
                                         boolean retryable,
                                         String safeMessage,
                                         Throwable cause,
                                         int attempts) {
        this(dependency, failureCode, retryable, safeMessage, cause, attempts, null, false, null, null, null);
    }

    private TechnicalDependencyException(TechnicalDependency dependency,
                                         TechnicalFailureCode failureCode,
                                         boolean retryable,
                                         String safeMessage,
                                         Throwable cause,
                                         int attempts,
                                         LvErrorCode lvCode,
                                         boolean rateLimited,
                                         Duration retryAfter,
                                         String destinationCode,
                                         Integer page) {
        super(safeMessage, cause);
        this.page = page != null && page >= 1 ? page : null;
        this.lvCode = lvCode;
        this.destinationCode = safeDestinationCode(destinationCode);
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
                dependency, failureCode, retryable, getMessage(), this, attemptCount, lvCode, rateLimited, retryAfter,
                destinationCode, page);
    }

    /**
     * Task 0025: the same failure with its precise LexaVault code, for an internal code that covers several causes
     * (for example DELIVERY_IO for "can't be reached", "busy" and "answer not understood"). Behaviour is unchanged.
     */
    public TechnicalDependencyException withLvCode(LvErrorCode code) {
        return new TechnicalDependencyException(dependency, failureCode, retryable, getMessage(), getCause(), attempts,
                code, rateLimited, retryAfter, destinationCode, page);
    }

    /**
     * Task 0028: the same failure marked as a 429 ("slow down"), with the wait it asked for (null when none). Only
     * export jobs read the mark; the code, retry and message are unchanged.
     */
    public TechnicalDependencyException asRateLimited(Duration wait) {
        Duration safeWait = wait == null || wait.isNegative() ? null : wait;
        return new TechnicalDependencyException(dependency, failureCode, retryable, getMessage(), getCause(), attempts,
                lvCode, true, safeWait, destinationCode, page);
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

    /**
     * Task 0030: the same failure with the destination's own error code from its answer. A code that is not safe
     * (see {@link #SAFE_DESTINATION_CODE}) is dropped. The code, retry and message are unchanged.
     */
    public TechnicalDependencyException withDestinationCode(String code) {
        return new TechnicalDependencyException(dependency, failureCode, retryable, getMessage(), getCause(), attempts,
                lvCode, rateLimited, retryAfter, code, page);
    }

    /** Task 0030: the destination's own code (safe pattern only), or null. */
    public String destinationCode() {
        return destinationCode;
    }

    /** Task 0030: the code when it matches the safe pattern, else null. */
    public static String safeDestinationCode(String raw) {
        return raw != null && SAFE_DESTINATION_CODE.matcher(raw).matches() ? raw : null;
    }

    /**
     * Task 0039: the same failure marked with the 1-based page it happened on. A number below 1 is dropped. The code,
     * retry, attempts and message are unchanged.
     */
    public TechnicalDependencyException withPage(int pageNumber) {
        return new TechnicalDependencyException(dependency, failureCode, retryable, getMessage(), getCause(), attempts,
                lvCode, rateLimited, retryAfter, destinationCode, pageNumber);
    }

    /** Task 0039: the 1-based page of a text-reading failure, or null when the failure is not about one page. */
    public Integer page() {
        return page;
    }
}
