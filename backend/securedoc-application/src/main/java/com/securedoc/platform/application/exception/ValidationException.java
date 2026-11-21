package com.securedoc.platform.application.exception;

import com.securedoc.platform.domain.error.LvErrorCode;

/**
 * Thrown when command input fails validation.
 *
 * <p>Task 0025: pass the LexaVault code the refusal belongs to ({@code LV-CFG-001} for a setting that breaks its rule,
 * {@code LV-INT-003} for a missing intake field, and so on). A refusal without one is reported as {@code LV-SYS-001}
 * and logged as a gap.
 */
public class ValidationException extends ApplicationException {

    public ValidationException(String message) {
        super("VALIDATION_ERROR", message);
    }

    public ValidationException(LvErrorCode lvCode, String message) {
        super("VALIDATION_ERROR", message, lvCode);
    }

    /** For subclasses with their own internal code. */
    protected ValidationException(String errorCode, String message, LvErrorCode lvCode) {
        super(errorCode, message, lvCode);
    }
}
