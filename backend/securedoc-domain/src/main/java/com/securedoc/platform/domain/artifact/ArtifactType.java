package com.securedoc.platform.domain.artifact;

/**
 * Classification of processing artifact type.
 */
public enum ArtifactType {

    /** Original uploaded file. */
    ORIGINAL,

    /** Embedded text extracted from a digital PDF. */
    EXTRACTED_TEXT,

    /** Task 0049: the text of one page batch, until the document's batches are gathered into EXTRACTED_TEXT. */
    EXTRACTED_TEXT_BATCH,

    /** Task 0049: the places one page batch found (no value, no text), until the document's batches are gathered. */
    FINDINGS_BATCH,

    /** Task 0064: what the vision model found on one page (kinds and boxes; no value), until it is gathered. */
    VISION_PAGE,

    /** Task 0065: the AI's short summary of the document, until its page batches are gathered. Document text. */
    DOCUMENT_SUMMARY,

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
