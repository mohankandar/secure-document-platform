package com.securedoc.platform.application.intake.model;

/**
 * Why an intake was refused. Stored by name in {@code intake_observations.reason_code} (VARCHAR(100), no check
 * constraint), so values can be added without a schema change.
 */
public enum IntakeRejectionCode {
    IDENTIFIER_CONFIGURATION_MISSING,
    IDENTIFIER_CONFIGURATION_INVALID,
    MISSING_REQUIRED_IDENTIFIER,
    UNKNOWN_IDENTIFIER,
    IDENTIFIER_CONFLICT,
    INVALID_IDENTIFIER_VALUE,
    MALFORMED_TRIGGER,
    MISSING_FILE,
    UNSUPPORTED_FILE,
    NO_DOCUMENTS,
    DUPLICATE_INTAKE,
    /** The drop could not be read from the folder (a real I/O fault). */
    SOURCE_IO_ERROR,
    /** Task 0023 C2: the tenant licence refused intake (the licence code is in the audit detail). */
    LICENSE_DENIED,
    /** Task 0023 C2: the tenant profile does not enable document upload. */
    CAPABILITY_DISABLED,
    /** Task 0023 C2: the tenant is not active. */
    TENANT_INACTIVE,
    /** Task 0023 C2: secure storage could not keep the original. */
    STORAGE_UNAVAILABLE,
    /** Task 0060: the document could not be stored encrypted; the source may send it again. */
    DOCUMENT_KEY_UNAVAILABLE,
    /** Task 0060: as above, because the key service refused the app or the customer has no keys. */
    DOCUMENT_KEY_REFUSED,
    /** Task 0023 C2: a file was refused (name, type, not a readable PDF, empty). */
    INVALID_FILE,
    /** Task 0023 C2: the intake identity was not allowed to take the drop in. */
    ACCESS_DENIED,
    /** Task 0023 C2: a resumed drop no longer matches the case already made from it. */
    RECOVERY_CONFLICT,
    /** Task 0023 C2: any other fault; the exception type is in the audit detail and the log. */
    INTERNAL_ERROR
}
