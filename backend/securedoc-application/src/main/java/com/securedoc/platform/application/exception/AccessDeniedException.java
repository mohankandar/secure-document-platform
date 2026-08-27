package com.securedoc.platform.application.exception;

/**
 * Thrown when the current principal does not have permission for the requested operation.
 */
public class AccessDeniedException extends ApplicationException {

    public AccessDeniedException(String message) {
        super("ACCESS_DENIED", message);
    }
}
