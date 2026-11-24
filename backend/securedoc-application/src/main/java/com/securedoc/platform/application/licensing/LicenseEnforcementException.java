package com.securedoc.platform.application.licensing;

import com.securedoc.platform.application.exception.ApplicationException;
import com.securedoc.platform.domain.licensing.LicenseValidationStatus;

import java.util.Optional;

/**
 * Refused by the licence. {@code errorCode} is the API's internal identifier (API version 1), {@link #platformCode()} the
 * licensing library's own code when it gave one (task 0057).
 */
public final class LicenseEnforcementException extends ApplicationException {
    private final LicenseValidationStatus status;
    private final String platformCode;

    public LicenseEnforcementException(String errorCode, String message, LicenseValidationStatus status) {
        this(errorCode, message, status, null);
    }

    public LicenseEnforcementException(String errorCode, String message, LicenseValidationStatus status,
                                       String platformCode) {
        super(errorCode, message);
        this.status = status;
        this.platformCode = platformCode;
    }

    public LicenseValidationStatus status() {
        return status;
    }

    public Optional<String> platformCode() {
        return Optional.ofNullable(platformCode);
    }

    public boolean serviceUnavailable() {
        return status == LicenseValidationStatus.CONFIGURATION_ERROR;
    }
}
