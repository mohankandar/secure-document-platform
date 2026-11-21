package com.securedoc.platform.application.exception;

import com.securedoc.platform.application.intake.model.IntakeRejectionCode;
import java.util.Objects;

public final class IntakeValidationException extends ValidationException {
    private final IntakeRejectionCode rejectionCode;
    public IntakeValidationException(IntakeRejectionCode code, String message) {
        this(code, message, null);
    }
    /**
     * Task 0026: with a detail for integrators that names the field (LexaVault's configured name) and its rule. The
     * message can hold what was sent, so it is never the detail.
     */
    public IntakeValidationException(IntakeRejectionCode code, String message, String detail) {
        super("VALIDATION_ERROR", message, null, detail);
        this.rejectionCode = Objects.requireNonNull(code);
    }
    public IntakeRejectionCode rejectionCode() { return rejectionCode; }
    @Override public String errorCode() { return "INTAKE_" + rejectionCode.name(); }
}
