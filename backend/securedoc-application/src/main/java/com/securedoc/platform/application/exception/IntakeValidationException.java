package com.securedoc.platform.application.exception;

import com.securedoc.platform.application.intake.model.IntakeRejectionCode;
import java.util.Objects;

public final class IntakeValidationException extends ValidationException {
    private final IntakeRejectionCode rejectionCode;
    public IntakeValidationException(IntakeRejectionCode code, String message) {
        super(message);
        this.rejectionCode = Objects.requireNonNull(code);
    }
    public IntakeRejectionCode rejectionCode() { return rejectionCode; }
    @Override public String errorCode() { return "INTAKE_" + rejectionCode.name(); }
}
