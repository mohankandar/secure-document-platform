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

    /** Credit card number. Kept distinct from bank/account identifiers for tenant policy clarity. */
    CREDIT_CARD_NUMBER,

    /** Bank or other financial account number (not enabled by default in the V1 baseline). */
    FINANCIAL_ACCOUNT,

    /** Credit score (contextually recognized numeric score). */
    CREDIT_SCORE,

    /** Medical record number or health info. */
    MEDICAL_RECORD,

    /** National ID, passport, or driver's license number. */
    GOVERNMENT_ID,

    /** IP address. */
    IP_ADDRESS,

    /** Custom/tenant-defined entity type. */
    CUSTOM
}
