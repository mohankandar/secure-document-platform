package com.securedoc.platform.domain.licensing;

/** Runtime validation state of a signed LexaVault tenant license. */
public enum LicenseValidationStatus {
    NOT_ENFORCED,
    MISSING,
    ACTIVE,
    NOT_YET_VALID,
    EXPIRED,
    INVALID_SIGNATURE,
    TENANT_MISMATCH,
    UNSUPPORTED_SCHEMA,
    MALFORMED,
    CONFIGURATION_ERROR
}
