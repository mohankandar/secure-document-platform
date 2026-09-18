package com.securedoc.platform.domain.business;

/** Canonical-value case normalization applied before lookup/storage validation. */
public enum BusinessIdentifierCaseNormalization {
    PRESERVE,
    UPPER,
    LOWER
}
