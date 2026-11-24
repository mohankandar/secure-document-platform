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
    CUSTOM,

    // Task 0043 (R1 part 1): specific pattern kinds, each off by default for every entity. The names are stable
    // keys (stored in findings and in tenant_redaction_kinds); never rename one. FINANCIAL_ACCOUNT and GOVERNMENT_ID
    // above stay unused (owner, 0043 answers); the catalog task decides whether to remove them.

    /** US bank routing number (ABA: 9 digits, 3-7-1 checksum), by its label or on a check's MICR line. */
    BANK_ROUTING_NUMBER,

    /** Bank account number (4-17 digits), by its label or in the account field of a check's MICR line. */
    BANK_ACCOUNT_NUMBER,

    /** US Individual Taxpayer Identification Number (9NN-NN-NNNN with the ITIN group ranges); personal. */
    ITIN,

    /** US Employer Identification Number (NN-NNNNNNN with a valid IRS prefix); a business number. */
    EIN,

    /** Driver's license number, only next to a driver's license label. */
    DRIVERS_LICENSE_NUMBER,

    /** Passport number, only next to a passport label. */
    PASSPORT_NUMBER,

    // Task 0064 (vision): stable keys, as above.

    /** A handwritten signature or initials. Off by default (an entity switches it on). */
    SIGNATURE,

    /** A photo of a person's face. Off by default; only the vision path finds it. */
    FACE_PHOTO,

    /**
     * Not a redaction kind: a page that had to be read by the vision model and was not (switched off, no model, wrong
     * model, unusable answer). It has no place and always waits for a person. Never configurable.
     */
    UNREAD_PAGE
}
