package com.securedoc.platform.domain.document.version;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongFunction;

/**
 * Task 0104 (owner rule: never refuse a business resend; design {@code docs/design/CASE_VERSIONS.md}, section 3): what an
 * arriving file is to its case, decided without reading the document's text. In this order:
 * <ol>
 *   <li>the same bytes (SHA-256) as any file the case ever received: skipped, never processed or counted again;</li>
 *   <li>the source names it: its document id, or a manifest's {@code replaces}: the next version of that document;</li>
 *   <li>the file's own identity: the PDF's permanent {@code /ID}, the XMP {@code DocumentID}, or an incremental update
 *       (it begins with all the bytes of an earlier version);</li>
 *   <li>the same file name, other bytes, no id: a new document marked as a possible version (a person links it);</li>
 *   <li>anything else: a new document.</li>
 * </ol>
 * A new version always follows the lineage's latest version. Nothing here refuses a file. Names and ids are compared as
 * the caller gives them (hashes); no value of a document is held.
 */
public final class DocumentVersionRecognition {

    private DocumentVersionRecognition() {}

    /** What the file is to the case. Stored names: never rename one. */
    public enum Outcome { SKIPPED_IDENTICAL, NEW_VERSION, POSSIBLE_VERSION, NEW_DOCUMENT }

    /** Which rule decided. Stored names: never rename one. */
    public enum Rule { IDENTICAL, SOURCE_ID, REPLACES, PDF_ID, XMP_ID, INCREMENTAL_UPDATE, SAME_NAME, NONE }

    /**
     * A document (any version) the case holds, or held: fingerprints stay for the life of the case.
     *
     * @param documentId       this version's document id
     * @param lineageId        the first version's id (the document across its versions)
     * @param versionNumber    1, 2, ...
     * @param sha256           of the file's bytes
     * @param sizeBytes        the file's size
     * @param nameHash         hash of the file name, or null
     * @param pdfId            hash of the first part of the PDF trailer's {@code /ID}, or null
     * @param xmpDocumentId    hash of the XMP {@code DocumentID}, or null
     * @param sourceDocumentId the source's own id for the document (API, manifest {@code documentId}), or null
     */
    public record Known(UUID documentId, UUID lineageId, int versionNumber, String sha256, long sizeBytes, String nameHash,
                        String pdfId, String xmpDocumentId, String sourceDocumentId) {
        public Known {
            Objects.requireNonNull(documentId, "documentId");
            Objects.requireNonNull(lineageId, "lineageId");
            Objects.requireNonNull(sha256, "sha256");
            if (versionNumber < 1) throw new IllegalArgumentException("versionNumber must be at least 1");
        }
    }

    /**
     * The arriving file.
     *
     * @param sha256           of its bytes
     * @param sizeBytes        its size
     * @param nameHash         hash of its file name, or null
     * @param sourceDocumentId the source's id for it, or null
     * @param replaces         a document id of this case the source says it replaces (manifest {@code replaces}, a
     *                         scan operator's pick), or null
     * @param pdfId            hash of the first part of its trailer {@code /ID}, or null
     * @param xmpDocumentId    hash of its XMP {@code DocumentID}, or null
     * @param prefixSha256     SHA-256 of its first n bytes, for n smaller than its size (asked only for such n)
     */
    public record Arrival(String sha256, long sizeBytes, String nameHash, String sourceDocumentId, String replaces,
                          String pdfId, String xmpDocumentId, LongFunction<String> prefixSha256) {
        public Arrival {
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(prefixSha256, "prefixSha256");
        }
    }

    /**
     * The decision.
     *
     * @param outcome            what the file is
     * @param rule               which rule decided
     * @param documentId         the identical document, for {@link Outcome#SKIPPED_IDENTICAL}; else null
     * @param lineageId          the lineage it continues, for {@link Outcome#NEW_VERSION}; else null (a new lineage)
     * @param versionNumber      its version number (1 for a new document)
     * @param replacesDocumentId the version it follows, for {@link Outcome#NEW_VERSION}; else null
     * @param possibleVersionOf  the lineage it may continue, for {@link Outcome#POSSIBLE_VERSION}; else null
     */
    public record Result(Outcome outcome, Rule rule, UUID documentId, UUID lineageId, int versionNumber,
                         UUID replacesDocumentId, UUID possibleVersionOf) {}

    public static Result recognise(Arrival file, List<Known> known) {
        Objects.requireNonNull(file, "file");
        List<Known> all = List.copyOf(known);
        for (Known k : all) {
            if (k.sha256().equals(file.sha256())) return new Result(Outcome.SKIPPED_IDENTICAL, Rule.IDENTICAL, k.documentId(), null, 0, null, null);
        }
        // 2. The source names it. A replaces that is not a document of this case is not trusted.
        if (file.replaces() != null) {
            Optional<UUID> lineage = all.stream().filter(k -> k.documentId().toString().equals(file.replaces())
                    || k.lineageId().toString().equals(file.replaces())).map(Known::lineageId).findFirst();
            if (lineage.isPresent()) return nextVersion(lineage.get(), all, Rule.REPLACES);
        }
        if (file.sourceDocumentId() != null) {
            Optional<UUID> lineage = lineageWhere(all, k -> file.sourceDocumentId().equals(k.sourceDocumentId()));
            // The source said what it is: a new id is a new document, not a guess by name.
            return lineage.map(l -> nextVersion(l, all, Rule.SOURCE_ID)).orElseGet(DocumentVersionRecognition::newDocument);
        }
        // 3. The file's own identity.
        if (file.pdfId() != null) {
            Optional<UUID> lineage = lineageWhere(all, k -> file.pdfId().equals(k.pdfId()));
            if (lineage.isPresent()) return nextVersion(lineage.get(), all, Rule.PDF_ID);
        }
        if (file.xmpDocumentId() != null) {
            Optional<UUID> lineage = lineageWhere(all, k -> file.xmpDocumentId().equals(k.xmpDocumentId()));
            if (lineage.isPresent()) return nextVersion(lineage.get(), all, Rule.XMP_ID);
        }
        for (Known k : all.stream().sorted(Comparator.comparingLong(Known::sizeBytes).reversed()).toList()) {
            if (k.sizeBytes() > 0 && k.sizeBytes() < file.sizeBytes() && k.sha256().equals(file.prefixSha256().apply(k.sizeBytes()))) {
                return nextVersion(k.lineageId(), all, Rule.INCREMENTAL_UPDATE);
            }
        }
        // 4. Same name, other bytes, no id: added, and marked for a person; never blocks.
        if (file.nameHash() != null) {
            Optional<UUID> lineage = lineageWhere(all, k -> file.nameHash().equals(k.nameHash()));
            if (lineage.isPresent()) return new Result(Outcome.POSSIBLE_VERSION, Rule.SAME_NAME, null, null, 1, null, lineage.get());
        }
        return newDocument();
    }

    private static Optional<UUID> lineageWhere(List<Known> all, java.util.function.Predicate<Known> test) {
        return all.stream().filter(test).map(Known::lineageId).findFirst();
    }

    private static Result nextVersion(UUID lineage, List<Known> all, Rule rule) {
        Known latest = all.stream().filter(k -> k.lineageId().equals(lineage)).max(Comparator.comparingInt(Known::versionNumber)).orElseThrow();
        return new Result(Outcome.NEW_VERSION, rule, null, lineage, latest.versionNumber() + 1, latest.documentId(), null);
    }

    private static Result newDocument() {
        return new Result(Outcome.NEW_DOCUMENT, Rule.NONE, null, null, 1, null, null);
    }
}
