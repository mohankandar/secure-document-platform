package com.securedoc.platform.application.exception;

import com.securedoc.platform.domain.error.LvErrorCode;

/**
 * Thrown when command input fails validation.
 *
 * <p>Task 0025: pass the LexaVault code the refusal belongs to ({@code LV-CFG-001} for a setting that breaks its rule,
 * {@code LV-INT-003} for a missing intake field, and so on). A refusal without one is reported as {@code LV-SYS-001}
 * and logged as a gap.
 *
 * <p>Task 0026: an optional {@link #detail()} for integrators names <em>what</em> is wrong (the field, header or part,
 * and the allowed range), never a value that was sent. It goes to the API body's {@code detail} field.
 */
public class ValidationException extends ApplicationException {

    private final String detail;

    public ValidationException(String message) {
        super("VALIDATION_ERROR", message);
        this.detail = null;
    }

    public ValidationException(LvErrorCode lvCode, String message) {
        this(lvCode, message, null);
    }

    /** With a detail for integrators: what is wrong, never a value sent. */
    public ValidationException(LvErrorCode lvCode, String message, String detail) {
        super("VALIDATION_ERROR", message, lvCode);
        this.detail = detail;
    }

    /** For subclasses with their own internal code. */
    protected ValidationException(String errorCode, String message, LvErrorCode lvCode) {
        this(errorCode, message, lvCode, null);
    }

    /** For subclasses with their own internal code and a detail. */
    protected ValidationException(String errorCode, String message, LvErrorCode lvCode, String detail) {
        super(errorCode, message, lvCode);
        this.detail = detail;
    }

    /** What is wrong, for integrators (field, header or part and the allowed range); {@code null} when not given. */
    public String detail() {
        return detail;
    }
}
