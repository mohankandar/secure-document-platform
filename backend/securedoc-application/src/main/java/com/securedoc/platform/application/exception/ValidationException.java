package com.securedoc.platform.application.exception;

/**
 * Thrown when command input fails validation.
 */
public class ValidationException extends ApplicationException {

    public ValidationException(String message) {
        super("VALIDATION_ERROR", message);
    }
}
