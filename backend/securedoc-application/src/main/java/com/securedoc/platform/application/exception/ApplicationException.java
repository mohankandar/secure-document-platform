package com.securedoc.platform.application.exception;

/**
 * Base exception for application-layer errors.
 * Subclasses represent specific failure modes that the API layer can map to HTTP responses.
 */
public class ApplicationException extends RuntimeException {

    private final String errorCode;

    public ApplicationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ApplicationException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
