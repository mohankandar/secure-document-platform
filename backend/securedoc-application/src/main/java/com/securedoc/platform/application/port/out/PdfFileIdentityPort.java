package com.securedoc.platform.application.port.out;

import java.io.InputStream;

/**
 * Task 0104: a PDF's own identity, read without its text, to recognise a new version of a document
 * ({@code docs/design/CASE_VERSIONS.md}, rule 3.3). Both values are SHA-256 hashes (lower-case hex) of the ids, never the
 * ids themselves; either is null when the file has none or can't be read. Never throws for a file it can't read: the
 * intake's own checks decide about such a file.
 */
public interface PdfFileIdentityPort {

    PdfIdentity read(InputStream content);

    /**
     * @param pdfIdHash         hash of the first part of the trailer's {@code /ID} (the permanent id), or null
     * @param xmpDocumentIdHash hash of the XMP {@code xmpMM:DocumentID}, or null
     */
    record PdfIdentity(String pdfIdHash, String xmpDocumentIdHash) {
        public static final PdfIdentity NONE = new PdfIdentity(null, null);
    }
}
