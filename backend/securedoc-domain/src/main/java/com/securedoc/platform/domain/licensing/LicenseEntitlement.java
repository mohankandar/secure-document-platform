package com.securedoc.platform.domain.licensing;

/** Product entitlements carried by a signed tenant license. */
public enum LicenseEntitlement {
    DOCUMENT_PROCESSING,
    SCANNER_INTAKE,
    WATCHED_FOLDER_INTAKE,
    REPORTING,
    DERIVED_ROUTING,
    LOCAL_AI
}
