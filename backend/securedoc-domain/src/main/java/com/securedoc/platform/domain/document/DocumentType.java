package com.securedoc.platform.domain.document;

/**
 * Classification of document content type.
 */
public enum DocumentType {

    /** Standard PDF document. */
    PDF,

    /** Scanned image (PNG, JPEG, TIFF). */
    IMAGE,

    /** Microsoft Word document. */
    WORD,

    /** Spreadsheet (Excel, CSV). */
    SPREADSHEET,

    /** Plain text file. */
    TEXT,

    /** Email message (.eml, .msg). */
    EMAIL,

    /** Other/unclassified document type. */
    OTHER
}
