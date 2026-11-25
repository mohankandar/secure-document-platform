package com.securedoc.platform.infrastructure.erasure;

import com.documentplatform.eraselog.EraseHandlerException;
import com.documentplatform.eraselog.EraseRequest;
import com.securedoc.platform.domain.artifact.ArtifactType;

import java.util.UUID;

/**
 * Task 0060: LexaVault's erase log entries: the product, the kinds and what the record id holds. Ids only.
 * A case is its id; a file is {@code <case>/<document>/<file>}.
 */
public final class EraseRecord {
    public static final String PRODUCT = "LEXAVAULT";
    public static final String CASE = "case";
    public static final String FILE = "file";

    /** Every stored file of a case except its split copies. */
    public static final String CASE_FILES = "case-files";
    /** An original. */
    public static final String DOCUMENT = "document";
    /** A redacted copy. */
    public static final String REDACTED_COPY = "redacted-copy";
    /** A split copy kept for its destination. */
    public static final String EXPORT_COPY = "export-copy";
    /** Text, findings and page batches made while a document was worked on. */
    public static final String WORKING_FILE = "working-file";
    /** A file no record points to. */
    public static final String UNRECORDED_FILE = "unrecorded-file";

    /** Task 0061: the case field values of a business record; platform-core's own kind and handler. */
    public static final String CASE_FIELDS = com.documentplatform.eraselog.fields.CaseFieldsEraseHandler.KIND;

    private EraseRecord() {
    }

    static String kind(ArtifactType type) {
        return switch (type) {
            case ORIGINAL -> DOCUMENT;
            case REDACTED -> REDACTED_COPY;
            case DERIVED_REDACTED -> EXPORT_COPY;
            case EXTRACTED_TEXT, EXTRACTED_TEXT_BATCH, FINDINGS_BATCH, VISION_PAGE, DOCUMENT_SUMMARY, OCR_TEXT, THUMBNAIL, EXTRACTED_DATA,
                 ANNOTATED -> WORKING_FILE;
        };
    }

    record FileId(UUID packageId, UUID documentId, UUID fileId) {
        static FileId of(EraseRequest request) {
            String[] parts = request.recordId().split("/", -1);
            if (!FILE.equals(request.recordType()) || parts.length != 3) throw unknown();
            return new FileId(id(parts[0]), id(parts[1]), id(parts[2]));
        }

        @Override
        public String toString() {
            return packageId + "/" + documentId + "/" + fileId;
        }
    }

    static UUID caseId(EraseRequest request) {
        if (!CASE.equals(request.recordType())) throw unknown();
        return id(request.recordId());
    }

    private static UUID id(String text) {
        try {
            UUID id = UUID.fromString(text);
            if (id.toString().equals(text)) return id;
        } catch (IllegalArgumentException notAnId) {
            // Refused below.
        }
        throw unknown();
    }

    private static EraseHandlerException unknown() {
        return new EraseHandlerException("LV_ERASE_RECORD_UNKNOWN", "the record id is not one LexaVault writes");
    }
}
