package com.securedoc.platform.application.licensing;

import com.securedoc.platform.application.exception.ApplicationException;
import com.securedoc.platform.domain.licensing.LicenseValidationStatus;

/** Fail-closed licensing rejection for tenant processing. */
public final class LicenseEnforcementException extends ApplicationException {
    private final LicenseValidationStatus status;

    public LicenseEnforcementException(String errorCode, String message, LicenseValidationStatus status) {
        super(errorCode, message);
        this.status = status;
    }

    public LicenseValidationStatus status() {
        return status;
    }

    public boolean serviceUnavailable() {
        return status == LicenseValidationStatus.CONFIGURATION_ERROR;
    }
}
