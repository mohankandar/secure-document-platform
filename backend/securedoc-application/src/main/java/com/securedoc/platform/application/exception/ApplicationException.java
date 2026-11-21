package com.securedoc.platform.application.exception;

import com.securedoc.platform.domain.error.LvCoded;
import com.securedoc.platform.domain.error.LvErrorCode;

/**
 * Base exception for application-layer errors.
 * Subclasses represent specific failure modes that the API layer can map to HTTP responses.
 *
 * <p>Task 0025: an exception may carry its LexaVault code ({@link #lvCode()}); the internal {@link #errorCode()} stays
 * as the detail (API version 1). Without one, {@code LvErrorCodes} maps the exception by its type and code.
 */
public class ApplicationException extends RuntimeException implements LvCoded {

    private final String errorCode;
    private final LvErrorCode lvCode;

    public ApplicationException(String errorCode, String message) {
        this(errorCode, message, (LvErrorCode) null);
    }

    public ApplicationException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.lvCode = null;
    }

    public ApplicationException(String errorCode, String message, LvErrorCode lvCode) {
        super(message);
        this.errorCode = errorCode;
        this.lvCode = lvCode;
    }

    public ApplicationException(String errorCode, String message, LvErrorCode lvCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.lvCode = lvCode;
    }

    public String errorCode() {
        return errorCode;
    }

    /** The LexaVault code this exception was raised with, or {@code null}. */
    @Override
    public LvErrorCode lvCode() {
        return lvCode;
    }
}
