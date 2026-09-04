package com.securedoc.platform.domain.artifact;

/**
 * Classification of processing artifact type.
 */
public enum ArtifactType {

    /** Original uploaded file. */
    ORIGINAL,

    /** Embedded text extracted from a digital PDF. */
    EXTRACTED_TEXT,

    /** OCR-extracted text output (scanned image documents). */
    OCR_TEXT,

    /** Redacted version of the document. */
    REDACTED,

    /** Redacted page subset created for an approved routing destination. */
    DERIVED_REDACTED,

    /** Thumbnail or preview image. */
    THUMBNAIL,

    /** Structured data extraction result (JSON/XML). */
    EXTRACTED_DATA,

    /** Annotated version (with finding markers). */
    ANNOTATED
}
