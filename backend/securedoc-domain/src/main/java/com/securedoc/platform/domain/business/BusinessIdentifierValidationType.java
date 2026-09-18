package com.securedoc.platform.domain.business;

/** Generic tenant-configured validator family for business identifiers. */
public enum BusinessIdentifierValidationType {
    TEXT,
    NUMERIC,
    ALPHANUMERIC,
    EMAIL,
    UUID,
    ALLOW_LIST,
    CUSTOM_PATTERN
}
