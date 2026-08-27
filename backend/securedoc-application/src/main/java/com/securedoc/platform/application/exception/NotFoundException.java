package com.securedoc.platform.application.exception;

/**
 * Thrown when a requested entity cannot be found.
 */
public class NotFoundException extends ApplicationException {

    public NotFoundException(String resourceType, String resourceId) {
        super("NOT_FOUND", resourceType + " not found: " + resourceId);
    }
}
