package com.securedoc.platform.application.exception;

import com.securedoc.platform.domain.error.LvErrorCode;

/**
 * Thrown when the current principal does not have permission for the requested operation.
 *
 * <p>Task 0025: without a code of its own it is {@code LV-AUT-006} "Not allowed".
 */
public class AccessDeniedException extends ApplicationException {

    public AccessDeniedException(String message) {
        super("ACCESS_DENIED", message);
    }

    public AccessDeniedException(LvErrorCode lvCode, String message) {
        super("ACCESS_DENIED", message, lvCode);
    }
}
