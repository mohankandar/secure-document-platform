package com.securedoc.platform.application.exception;

public final class IntakeExpiredException extends ApplicationException {
    public IntakeExpiredException(String message) { super("INTAKE_EXPIRED", message); }
}
