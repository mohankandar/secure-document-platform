package com.securedoc.platform.domain.document;

/**
 * Lifecycle status of a document within a package.
 */
public enum DocumentStatus {

    /** Document uploaded, awaiting processing. */
    UPLOADED,

    /** OCR/text extraction in progress. */
    EXTRACTING,

    /** Extraction complete, sensitive-data detection in progress. */
    DETECTING,

    /** Detection complete, redaction in progress. */
    REDACTING,

    /** All processing complete. */
    PROCESSED,

    /** Processing failed for this document. */
    FAILED
}
