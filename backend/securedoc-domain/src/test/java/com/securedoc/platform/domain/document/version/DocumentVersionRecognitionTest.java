package com.securedoc.platform.domain.document.version;

import com.securedoc.platform.domain.document.version.DocumentVersionRecognition.Arrival;
import com.securedoc.platform.domain.document.version.DocumentVersionRecognition.Known;
import com.securedoc.platform.domain.document.version.DocumentVersionRecognition.Outcome;
import com.securedoc.platform.domain.document.version.DocumentVersionRecognition.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0104: how an arriving file is recognised against everything the case ever received, without reading text. */
class DocumentVersionRecognitionTest {

    private static final UUID LINEAGE_A = UUID.randomUUID();
    private static final UUID A_V1 = LINEAGE_A;
    private static final UUID A_V2 = UUID.randomUUID();
    private static final UUID LINEAGE_B = UUID.randomUUID();

    /** Document A in two versions, document B in one; plus an exact copy of A v1 that was skipped once. */
    private static final List<Known> CASE = List.of(
            new Known(A_V1, LINEAGE_A, 1, "sha-a1", 1000, "name-a", "pdfid-a", "xmp-a", "SRC-A"),
            new Known(A_V2, LINEAGE_A, 2, "sha-a2", 1400, "name-a", "pdfid-a", "xmp-a", "SRC-A"),
            new Known(LINEAGE_B, LINEAGE_B, 1, "sha-b1", 800, "name-b", "pdfid-b", null, null));

    private static Arrival arrival(String sha, String name) {
        return new Arrival(sha, 2000, name, null, null, null, null, length -> "no-prefix");
    }

    @Test
    void theSameBytesAsAnyVersionEverReceivedAreSkipped() {
        // Also an old version (v1), whatever its name, and with ids that point elsewhere.
        Result r = DocumentVersionRecognition.recognise(
                new Arrival("sha-a1", 1000, "renamed", "SRC-B", null, "pdfid-b", null, length -> "x"), CASE);
        assertEquals(Outcome.SKIPPED_IDENTICAL, r.outcome());
        assertEquals(A_V1, r.documentId());
    }

    @Test
    void theSourcesDocumentIdMakesTheNextVersionOfThatLineage() {
        Result r = DocumentVersionRecognition.recognise(new Arrival("sha-new", 2000, "other-name", "SRC-A", null, null, null, l -> "x"), CASE);
        assertEquals(Outcome.NEW_VERSION, r.outcome());
        assertEquals(LINEAGE_A, r.lineageId());
        assertEquals(3, r.versionNumber());
        assertEquals(A_V2, r.replacesDocumentId(), "it follows the latest version, never an older one");
        assertEquals(DocumentVersionRecognition.Rule.SOURCE_ID, r.rule());
    }

    @Test
    void aManifestReplacesNamesTheDocumentDirectly() {
        Result r = DocumentVersionRecognition.recognise(
                new Arrival("sha-new", 2000, "x", null, LINEAGE_B.toString(), null, null, l -> "x"), CASE);
        assertEquals(Outcome.NEW_VERSION, r.outcome());
        assertEquals(LINEAGE_B, r.lineageId());
        assertEquals(2, r.versionNumber());
    }

    @Test
    void aSourceIdThatNamesNothingKnownIsANewDocument() {
        Result r = DocumentVersionRecognition.recognise(new Arrival("sha-new", 2000, "name-a", "SRC-NEW", null, null, null, l -> "x"), CASE);
        assertEquals(Outcome.NEW_DOCUMENT, r.outcome(), "the source said what it is: not a guess by name");
    }

    @Test
    void thePdfsPermanentIdOrItsXmpDocumentIdMakeANewVersion() {
        Result byId = DocumentVersionRecognition.recognise(new Arrival("sha-new", 2000, "x", null, null, "pdfid-b", null, l -> "x"), CASE);
        assertEquals(Outcome.NEW_VERSION, byId.outcome());
        assertEquals(LINEAGE_B, byId.lineageId());
        assertEquals(DocumentVersionRecognition.Rule.PDF_ID, byId.rule());

        Result byXmp = DocumentVersionRecognition.recognise(new Arrival("sha-new", 2000, "x", null, null, null, "xmp-a", l -> "x"), CASE);
        assertEquals(LINEAGE_A, byXmp.lineageId());
        assertEquals(DocumentVersionRecognition.Rule.XMP_ID, byXmp.rule());
    }

    @Test
    void anIncrementalUpdateBeginsWithTheBytesOfAnEarlierVersion() {
        // A re-signed PDF: the first 1400 bytes are exactly version 2.
        Map<Long, String> prefixes = Map.of(1400L, "sha-a2", 1000L, "something-else", 800L, "something-else");
        Result r = DocumentVersionRecognition.recognise(
                new Arrival("sha-new", 2000, "x", null, null, null, null, l -> prefixes.getOrDefault(l, "none")), CASE);
        assertEquals(Outcome.NEW_VERSION, r.outcome());
        assertEquals(LINEAGE_A, r.lineageId());
        assertEquals(3, r.versionNumber());
        assertEquals(DocumentVersionRecognition.Rule.INCREMENTAL_UPDATE, r.rule());
    }

    @Test
    void aSmallerFileIsNeverTakenForAnIncrementalUpdate() {
        Result r = DocumentVersionRecognition.recognise(
                new Arrival("sha-new", 500, "x", null, null, null, null, l -> { throw new AssertionError("no prefix of a longer length"); }), CASE);
        assertEquals(Outcome.NEW_DOCUMENT, r.outcome());
    }

    @Test
    void theSameNameWithOtherBytesAndNoIdIsAddedAndMarkedNeverRefused() {
        Result r = DocumentVersionRecognition.recognise(arrival("sha-new", "name-b"), CASE);
        assertEquals(Outcome.POSSIBLE_VERSION, r.outcome());
        assertEquals(LINEAGE_B, r.possibleVersionOf());
        assertNull(r.lineageId(), "it is its own new document until a person links it");
        assertEquals(1, r.versionNumber());
    }

    @Test
    void anythingElseIsANewDocument() {
        Result r = DocumentVersionRecognition.recognise(arrival("sha-new", "name-new"), CASE);
        assertEquals(Outcome.NEW_DOCUMENT, r.outcome());
        assertEquals(1, r.versionNumber());
        assertEquals(Outcome.NEW_DOCUMENT, DocumentVersionRecognition.recognise(arrival("sha-new", "name-new"), List.of()).outcome());
    }

    @Test
    void theSourceWinsOverTheFilesOwnIdentity() {
        // The source names B, the file's /ID says A: the source decides (rule 3.2 before 3.3).
        Result r = DocumentVersionRecognition.recognise(
                new Arrival("sha-new", 2000, "x", null, LINEAGE_B.toString(), "pdfid-a", null, l -> "x"), CASE);
        assertEquals(LINEAGE_B, r.lineageId());
    }

    @Test
    void aReplacesThatIsNotADocumentOfTheCaseIsNotTrusted() {
        Result r = DocumentVersionRecognition.recognise(
                new Arrival("sha-new", 2000, "name-new", null, UUID.randomUUID().toString(), null, null, l -> "x"), CASE);
        assertEquals(Outcome.NEW_DOCUMENT, r.outcome(), "never a version of another case's document");
    }
}
