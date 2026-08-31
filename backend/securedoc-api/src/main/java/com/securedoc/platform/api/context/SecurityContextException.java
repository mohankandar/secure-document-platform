package com.securedoc.platform.api.context;

/**
 * Thrown when the configured authentication trust boundary cannot resolve a safe application principal.
 * Mapped to a safe fail-closed response by the global exception handler.
 */
public class SecurityContextException extends RuntimeException {

    public SecurityContextException(String message) {
        super(message);
    }
}
