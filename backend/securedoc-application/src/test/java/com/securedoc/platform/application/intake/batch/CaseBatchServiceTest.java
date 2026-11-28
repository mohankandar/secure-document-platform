package com.securedoc.platform.application.intake.batch;

import com.securedoc.platform.application.port.out.CaseBatchStore;
import com.securedoc.platform.domain.document.version.DocumentVersionRecognition.Arrival;
import com.securedoc.platform.domain.document.version.DocumentVersionRecognition.Known;
import com.securedoc.platform.domain.document.version.DocumentVersionRecognition.Outcome;
import com.securedoc.platform.domain.document.version.DocumentVersionRecognition.Result;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0104: a batch is classified file by file against the case and against the files before it in the same batch. */
class CaseBatchServiceTest {

    private final TenantId tenant = TenantId.generate();
    private final PackageId pkg = PackageId.generate();
    private final UUID docA = UUID.randomUUID();
    private final List<CaseBatchStore.ReceivedFile> recorded = new ArrayList<>();

    private final CaseBatchStore store = new CaseBatchStore() {
        @Override public List<Known> knownVersions(TenantId t, PackageId p) {
            return List.of(new Known(docA, docA, 1, CaseBatchService.sha256("contract v1".getBytes(StandardCharsets.UTF_8)),
                    11, CaseBatchService.nameHash("contract.pdf"), null, null, null));
        }
        @Override public java.util.Optional<PackageId> openCaseFor(TenantId t, UUID record) { return java.util.Optional.empty(); }
        @Override public Batch openBatch(TenantId t, PackageId p, String source, Instant at) { return new Batch(UUID.randomUUID(), 2); }
        @Override public void recordFile(TenantId t, PackageId p, UUID batchId, ReceivedFile file) { recorded.add(file); }
        @Override public void setVersion(TenantId t, UUID d, UUID b, Result r, String pdf, String xmp, String src) { }
        @Override public java.util.Optional<BatchState> latestBatch(TenantId t, PackageId p) { return java.util.Optional.empty(); }
        @Override public java.util.Set<String> recordedFiles(TenantId t, UUID batchId) { return java.util.Set.of(); }
        @Override public void markNothingNew(TenantId t, UUID batchId) { }
    };

    private final CaseBatchService service = new CaseBatchService(store);

    private static Arrival file(String content, String name) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return CaseBatchService.arrival(bytes.length, () -> new ByteArrayInputStream(bytes), name, null, null, null, null);
    }

    @Test
    void aResentPackageIsSkippedWholeAndAChangedFileIsANewDocumentOrVersion() {
        List<CaseBatchService.Decision> d = service.classify(tenant, pkg, List.of(
                file("contract v1", "contract.pdf"),        // the same bytes: skipped
                file("contract v1 re-signed", "contract.pdf"), // begins with v1's bytes: incremental update
                file("a new letter", "letter.pdf"),
                file("a new letter", "letter-copy.pdf")));  // the same bytes as the file before it in this batch
        assertEquals(Outcome.SKIPPED_IDENTICAL, d.get(0).result().outcome());
        assertEquals(docA, d.get(0).result().documentId());
        assertNull(d.get(0).newDocumentId(), "a skipped copy is not stored");
        assertEquals(Outcome.NEW_VERSION, d.get(1).result().outcome());
        assertEquals(2, d.get(1).result().versionNumber());
        assertNotNull(d.get(1).newDocumentId());
        assertEquals(Outcome.NEW_DOCUMENT, d.get(2).result().outcome());
        assertEquals(d.get(2).newDocumentId(), d.get(2).result().lineageId(), "a new document is its own lineage");
        assertEquals(Outcome.SKIPPED_IDENTICAL, d.get(3).result().outcome(), "nothing in one batch is processed twice");
        assertEquals(d.get(2).newDocumentId(), d.get(3).result().documentId(), "skipped for the file before it in the batch");
    }

    @Test
    void twoNewVersionsOfOneDocumentInOneBatchFollowEachOther() {
        List<CaseBatchService.Decision> d = service.classify(tenant, pkg, List.of(
                CaseBatchService.arrival(3, () -> new ByteArrayInputStream("one".getBytes()), "x", null, docA.toString(), null, null),
                CaseBatchService.arrival(3, () -> new ByteArrayInputStream("two".getBytes()), "y", null, docA.toString(), null, null)));
        assertEquals(2, d.get(0).result().versionNumber());
        assertEquals(3, d.get(1).result().versionNumber());
        assertEquals(d.get(0).newDocumentId(), d.get(1).result().replacesDocumentId());
    }

    @Test
    void theHashesAreLowerCaseHexAndANameIsNeverKeptAsItIs() {
        assertEquals(64, CaseBatchService.nameHash("Jane Doe loan.pdf").length());
        assertFalse(CaseBatchService.nameHash("Jane Doe loan.pdf").contains("Jane"));
        assertNull(CaseBatchService.nameHash(null));
        assertEquals(CaseBatchService.nameHash("a.pdf"), CaseBatchService.nameHash("A.PDF"), "names compare without case");
    }
}
