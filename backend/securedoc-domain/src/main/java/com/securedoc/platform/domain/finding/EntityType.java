package com.securedoc.platform.domain.finding;

/**
 * Classification of sensitive entity types that can be detected in documents.
 */
public enum EntityType {

    /** Personal name. */
    PERSON_NAME,

    /** Social security number or equivalent. */
    SSN,

    /** Date of birth. */
    DATE_OF_BIRTH,

    /** Physical address. */
    ADDRESS,

    /** Phone number. */
    PHONE_NUMBER,

    /** Email address. */
    EMAIL_ADDRESS,

    /** Credit card or bank account number. */
    FINANCIAL_ACCOUNT,

    /** Medical record number or health info. */
    MEDICAL_RECORD,

    /** National ID, passport, or driver's license number. */
    GOVERNMENT_ID,

    /** IP address. */
    IP_ADDRESS,

    /** Custom/tenant-defined entity type. */
    CUSTOM
}
