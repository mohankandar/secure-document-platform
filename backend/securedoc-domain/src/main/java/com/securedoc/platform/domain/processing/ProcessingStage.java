package com.securedoc.platform.domain.processing;

/**
 * Stable processing-stage taxonomy for technical incidents.
 *
 * <p>The enum intentionally includes current and foreseeable stages so support data does not
 * become AI-specific. New processors should reuse the closest stable stage before introducing
 * another value.
 */
public enum ProcessingStage {
    INTAKE,
    TEXT_EXTRACTION,
    OCR,
    AI_ANALYSIS,
    SENSITIVE_DETECTION,
    DOCUMENT_PROFILE,
    IDENTIFIER_EXTRACTION,
    MANUAL_REVIEW,
    REDACTION,
    VERIFICATION,
    DERIVED_DOCUMENT_SPLIT,
    ROUTING,
    DELIVERY,
    STORAGE,
    RETENTION,
    MIGRATION_REVALIDATION,
    DOCUMENT_PROCESSING,
    UNKNOWN
}
