package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.application.intake.batch.CaseBatchService;
import com.securedoc.platform.application.port.out.CaseBatchStore;
import com.securedoc.platform.domain.document.version.DocumentVersionRecognition.Known;
import com.securedoc.platform.domain.document.version.DocumentVersionRecognition.Outcome;
import com.securedoc.platform.domain.document.version.DocumentVersionRecognition.Rule;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.intake.JdbcCaseBatchStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0104: batches are numbered per case; every received file is kept with its outcome; a version's fingerprint stays
 * known after its document row is gone (purged original), so a file arriving months later is still a skipped copy.
 */
@EnabledIfEnvironmentVariable(named = "LEXAVAULT_TEST_PG_URL", matches = "jdbc:postgresql:.*")
class JdbcCaseBatchStorePostgresTest {

    @Test
    void batchesFilesAndVersionsRoundTripAndFingerprintsOutliveTheDocument() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.migrated("cbs")) {
            NamedParameterJdbcTemplate jdbc = db.jdbc();
            TenantId tenant = TenantId.generate();
            TestRows.tenant(jdbc, tenant);
            UUID pkgId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO document_packages (id, tenant_id, package_type, status, created_by, created_at, updated_at)
                    VALUES (:id, :tenant, 'FOLDER_INTAKE', 'COMPLETED', :actor, now(), now())
                    """, new MapSqlParameterSource("id", pkgId).addValue("tenant", tenant.value()).addValue("actor", UUID.randomUUID()));
            PackageId pkg = PackageId.of(pkgId);
            JdbcCaseBatchStore store = new JdbcCaseBatchStore(jdbc);

            CaseBatchStore.Batch first = store.openBatch(tenant, pkg, "WATCHED_FOLDER", Instant.now());
            CaseBatchStore.Batch second = store.openBatch(tenant, pkg, "WATCHED_FOLDER", Instant.now());
            assertEquals(1, first.number());
            assertEquals(2, second.number());

            byte[] v1 = "contract v1".getBytes();
            CaseBatchService service = new CaseBatchService(store);
            var d1 = service.classify(tenant, pkg, List.of(CaseBatchService.arrival(v1.length, () -> new ByteArrayInputStream(v1),
                    "contract.pdf", null, null, "pdf-id-1", null))).get(0);
            assertEquals(Outcome.NEW_DOCUMENT, d1.result().outcome());
            // The caller stores the file under the id the classification gave it.
            UUID docV1 = document(jdbc, tenant, pkgId, d1.newDocumentId());
            store.setVersion(tenant, docV1, first.id(), d1.result(), "pdf-id-1", null, null);
            store.recordFile(tenant, pkg, first.id(), new CaseBatchStore.ReceivedFile(CaseBatchService.sha256(v1), v1.length, 1,
                    CaseBatchService.nameHash("contract.pdf"), Outcome.NEW_DOCUMENT, Rule.NONE, docV1));

            List<Known> known = store.knownVersions(tenant, pkg);
            assertEquals(1, known.size());
            assertEquals(docV1, known.get(0).lineageId(), "a new document is its own lineage");
            assertEquals("pdf-id-1", known.get(0).pdfId());

            // A new version by the PDF's id, stored and recorded.
            byte[] v2 = "contract v2, all new bytes".getBytes();
            var d2 = service.classify(tenant, pkg, List.of(CaseBatchService.arrival(v2.length, () -> new ByteArrayInputStream(v2),
                    "renamed.pdf", null, null, "pdf-id-1", null))).get(0);
            assertEquals(Outcome.NEW_VERSION, d2.result().outcome());
            UUID docV2 = document(jdbc, tenant, pkgId, d2.newDocumentId());
            store.setVersion(tenant, docV2, second.id(), d2.result(), "pdf-id-1", null, null);
            store.recordFile(tenant, pkg, second.id(), new CaseBatchStore.ReceivedFile(CaseBatchService.sha256(v2), v2.length, 1,
                    null, Outcome.NEW_VERSION, d2.result().rule(), docV2));
            assertEquals(2, jdbc.queryForObject("SELECT version_number FROM documents WHERE id = :id",
                    new MapSqlParameterSource("id", docV2), Integer.class));
            assertEquals(docV1, jdbc.queryForObject("SELECT replaces_document_id FROM documents WHERE id = :id",
                    new MapSqlParameterSource("id", docV2), UUID.class));

            // The original of version 1 is purged and its row is gone: its fingerprint still makes a resend a skipped copy.
            jdbc.update("DELETE FROM documents WHERE id = :id", new MapSqlParameterSource("id", docV1));
            var d3 = service.classify(tenant, pkg, List.of(CaseBatchService.arrival(v1.length, () -> new ByteArrayInputStream(v1),
                    "anything.pdf", null, null, null, null))).get(0);
            assertEquals(Outcome.SKIPPED_IDENTICAL, d3.result().outcome());
            assertEquals(docV1, d3.result().documentId());
        }
    }

    @Test
    void theOpenCaseOfAContractIsTheNewestNotRejectedOrCancelledAlsoAfterItsFilesWereDeleted() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.migrated("cbsopen")) {
            NamedParameterJdbcTemplate jdbc = db.jdbc();
            TenantId tenant = TenantId.generate();
            TenantId other = TenantId.generate();
            TestRows.tenant(jdbc, tenant);
            TestRows.tenant(jdbc, other);
            JdbcCaseBatchStore store = new JdbcCaseBatchStore(jdbc);
            UUID record = UUID.randomUUID();

            assertTrue(store.openCaseFor(tenant, record).isEmpty(), "no case yet: the arrival makes one");
            UUID delivered = pkg(jdbc, tenant, record, "COMPLETED", "2026-11-01T10:00:00Z");
            assertEquals(delivered, store.openCaseFor(tenant, record).orElseThrow().value());

            pkg(jdbc, tenant, record, "REJECTED", "2026-11-02T10:00:00Z");
            pkg(jdbc, tenant, record, "CANCELLED", "2026-11-03T10:00:00Z");
            assertEquals(delivered, store.openCaseFor(tenant, record).orElseThrow().value(),
                    "a rejected or cancelled case takes no batch");
            // Owner 2026-11-28 (decision (a)): a case whose files were deleted by retention still takes the next batch.
            UUID purged = pkg(jdbc, tenant, record, "COMPLETED", "2026-11-04T10:00:00Z");
            jdbc.update("INSERT INTO package_lifecycles (package_id, tenant_id, successful_export_cycles, purged_at, updated_at) VALUES (:p, :t, 1, now(), now())",
                    new MapSqlParameterSource("p", purged).addValue("t", tenant.value()));
            assertEquals(purged, store.openCaseFor(tenant, record).orElseThrow().value());
            UUID delivered2 = purged;

            pkg(jdbc, tenant, UUID.randomUUID(), "COMPLETED", "2026-11-05T10:00:00Z");
            pkg(jdbc, other, record, "COMPLETED", "2026-11-06T10:00:00Z");
            assertEquals(delivered2, store.openCaseFor(tenant, record).orElseThrow().value(), "never another contract or entity");
            assertTrue(store.openCaseFor(tenant, null).isEmpty());
        }
    }


    /**
     * Owner 2026-11-28: a contract whose case-field values were erased is still found by its keyed fingerprint (any key
     * version), never another entity's; the fingerprint is kept once per case and goes when the case goes; the batch
     * history can say the earlier files were deleted.
     */
    @Test
    void aContractIsFoundByItsKeyedFingerprintAfterItsValuesAreGoneAndTheFingerprintGoesWithTheCase() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.migrated("cbskey")) {
            NamedParameterJdbcTemplate jdbc = db.jdbc();
            TenantId tenant = TenantId.generate();
            TenantId other = TenantId.generate();
            TestRows.tenant(jdbc, tenant);
            TestRows.tenant(jdbc, other);
            JdbcCaseBatchStore store = new JdbcCaseBatchStore(jdbc);
            UUID erasedRecord = UUID.randomUUID();
            UUID kase = pkg(jdbc, tenant, erasedRecord, "COMPLETED", "2026-10-01T10:00:00Z");
            UUID othersCase = pkg(jdbc, other, UUID.randomUUID(), "COMPLETED", "2026-10-02T10:00:00Z");

            assertTrue(store.openCaseForContract(tenant, List.of("1:mac-a")).isEmpty());
            store.rememberContract(tenant, PackageId.of(kase), "1:mac-a");
            store.rememberContract(tenant, PackageId.of(kase), "1:mac-a");
            store.rememberContract(other, PackageId.of(othersCase), "1:mac-b");
            store.rememberContract(other, PackageId.of(kase), "1:mac-c"); // another entity's call never writes on this case
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM case_contract_keys WHERE package_id = :p",
                    new MapSqlParameterSource("p", kase), Integer.class), "once per case and key version");

            assertEquals(kase, store.openCaseForContract(tenant, List.of("2:mac-new", "1:mac-a")).orElseThrow().value(),
                    "found by an older key version");
            assertTrue(store.openCaseForContract(tenant, List.of("1:mac-b")).isEmpty(), "never another entity's case");
            assertTrue(store.openCaseForContract(tenant, List.of()).isEmpty());
            jdbc.update("UPDATE document_packages SET status = 'REJECTED' WHERE id = :p", new MapSqlParameterSource("p", kase));
            assertTrue(store.openCaseForContract(tenant, List.of("1:mac-a")).isEmpty(), "a rejected case takes no batch");

            CaseBatchStore.Batch batch = store.openBatch(tenant, PackageId.of(kase), "WATCHED_FOLDER", Instant.now());
            store.markEarlierFilesDeleted(tenant, batch.id());
            assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT earlier_files_deleted FROM case_batches WHERE id = :b",
                    new MapSqlParameterSource("b", batch.id()), Boolean.class));

            jdbc.update("DELETE FROM case_batches WHERE package_id = :p", new MapSqlParameterSource("p", kase));
            jdbc.update("DELETE FROM document_packages WHERE id = :p", new MapSqlParameterSource("p", kase));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM case_contract_keys WHERE package_id = :p",
                    new MapSqlParameterSource("p", kase), Integer.class), "the fingerprint goes with the case");
        }
    }
    @Test
    void theCurrentBatchScopesTheExportAndItsCycleLimit() throws Exception {
        // Task 0104, step 3: a case of one batch delivers every document; a later batch only its own, and the cycles that
        // delivered the batches before it do not count against its limit.
        try (PostgresTestDatabase db = PostgresTestDatabase.migrated("cbsscope")) {
            NamedParameterJdbcTemplate jdbc = db.jdbc();
            TenantId tenant = TenantId.generate();
            TestRows.tenant(jdbc, tenant);
            UUID pkgId = pkg(jdbc, tenant, UUID.randomUUID(), "COMPLETED", "2026-11-01T10:00:00Z");
            PackageId pkg = PackageId.of(pkgId);
            JdbcCaseBatchStore store = new JdbcCaseBatchStore(jdbc);

            assertEquals(0, store.cyclesBeforeCurrentBatch(tenant, pkg), "no batches: as before");
            assertTrue(store.currentBatchDocuments(tenant, pkg).isEmpty());
            store.currentBatchDelivered(tenant, pkg, 1); // no batch row: nothing to mark, no failure

            CaseBatchStore.Batch first = store.openBatch(tenant, pkg, "WATCHED_FOLDER", Instant.now());
            UUID v1 = document(jdbc, tenant, pkgId, UUID.randomUUID());
            jdbc.update("UPDATE documents SET batch_id = :b WHERE id = :id", new MapSqlParameterSource("b", first.id()).addValue("id", v1));
            assertTrue(store.currentBatchDocuments(tenant, pkg).isEmpty(), "batch 1 delivers every document");
            store.currentBatchDelivered(tenant, pkg, 1);
            store.currentBatchDelivered(tenant, pkg, 2); // a re-export of batch 1
            assertEquals(0, store.cyclesBeforeCurrentBatch(tenant, pkg));

            CaseBatchStore.Batch second = store.openBatch(tenant, pkg, "WATCHED_FOLDER", Instant.now());
            UUID v2 = document(jdbc, tenant, pkgId, UUID.randomUUID());
            jdbc.update("UPDATE documents SET batch_id = :b WHERE id = :id", new MapSqlParameterSource("b", second.id()).addValue("id", v2));
            assertEquals(java.util.Set.of(v2), store.currentBatchDocuments(tenant, pkg).orElseThrow(), "only the new batch's documents");
            assertEquals(2, store.cyclesBeforeCurrentBatch(tenant, pkg), "batch 1 took cycles 1 and 2");

            store.currentBatchDelivered(tenant, pkg, 3);
            assertEquals(3, jdbc.queryForObject("SELECT export_cycle FROM case_batches WHERE id = :id",
                    new MapSqlParameterSource("id", second.id()), Integer.class));
            assertEquals("DELIVERED", jdbc.queryForObject("SELECT state FROM case_batches WHERE id = :id",
                    new MapSqlParameterSource("id", second.id()), String.class));
            assertEquals(2, store.cyclesBeforeCurrentBatch(tenant, pkg), "its own cycles never count as before it");
        }
    }

    /** A case made by an intake without batches (after migration 093): its documents become batch 1 when batch 2 comes. */
    @Test
    void aCaseMadeWithoutBatchesAdoptsItsDocumentsAsBatchOneWhenTheNextBatchComes() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.migrated("cbs3")) {
            NamedParameterJdbcTemplate jdbc = db.jdbc();
            TenantId tenant = TenantId.generate();
            TestRows.tenant(jdbc, tenant);
            UUID pkgId = pkg(jdbc, tenant, UUID.randomUUID(), "COMPLETED", "2026-11-01T10:00:00Z");
            PackageId pkg = PackageId.of(pkgId);
            UUID delivered = document(jdbc, tenant, pkgId, UUID.randomUUID());
            jdbc.update("UPDATE documents SET checksum_value = :sha WHERE id = :id", new MapSqlParameterSource("id", delivered)
                    .addValue("sha", CaseBatchService.sha256("contract v1".getBytes()).toUpperCase(java.util.Locale.ROOT)));
            jdbc.update("INSERT INTO package_lifecycles (package_id, tenant_id, successful_export_cycles, updated_at) VALUES (:p, :t, 2, now())",
                    new MapSqlParameterSource("p", pkgId).addValue("t", tenant.value()));
            JdbcCaseBatchStore store = new JdbcCaseBatchStore(jdbc);

            CaseBatchStore.Batch next = store.openBatch(tenant, pkg, "WATCHED_FOLDER", Instant.now());
            assertEquals(2, next.number(), "the case's first arrival is batch 1");
            assertEquals(List.of(delivered), store.knownVersions(tenant, pkg).stream().map(Known::documentId).toList());
            assertEquals(CaseBatchService.sha256("contract v1".getBytes()), store.knownVersions(tenant, pkg).getFirst().sha256(),
                    "compared by its fingerprint, lower case");
            assertEquals(2, store.cyclesBeforeCurrentBatch(tenant, pkg), "the cycles that delivered batch 1");
            assertEquals(pkgId, jdbc.queryForObject("SELECT batch_id FROM documents WHERE id = :id",
                    new MapSqlParameterSource("id", delivered), UUID.class));
            store.openBatch(tenant, pkg, "API", Instant.now());
            assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM case_batches WHERE package_id = :p",
                    new MapSqlParameterSource("p", pkgId), Integer.class), "adopted once only");
            assertEquals(1, store.knownVersions(tenant, pkg).size());

            // A batch of exact copies only: kept in the history, never the current batch to deliver.
            CaseBatchStore.Batch copies = store.latestBatch(tenant, pkg).map(b -> new CaseBatchStore.Batch(b.id(), b.number())).orElseThrow();
            store.recordFile(tenant, pkg, copies.id(), new CaseBatchStore.ReceivedFile(CaseBatchService.sha256("contract v1".getBytes()),
                    11, null, null, Outcome.SKIPPED_IDENTICAL, Rule.IDENTICAL, delivered));
            store.markNothingNew(tenant, copies.id());
            assertEquals("NOTHING_NEW", store.latestBatch(tenant, pkg).orElseThrow().state());
            assertEquals(java.util.Set.of(CaseBatchService.sha256("contract v1".getBytes())), store.recordedFiles(tenant, copies.id()));
            assertEquals(java.util.Set.of(), store.currentBatchDocuments(tenant, pkg).orElseThrow(),
                    "the current batch is batch 2 (no documents yet), not the batch of copies");
            assertEquals(2, store.cyclesBeforeCurrentBatch(tenant, pkg));
        }
    }

    private static UUID pkg(NamedParameterJdbcTemplate jdbc, TenantId tenant, UUID record, String status, String at) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO document_packages (id, tenant_id, package_type, status, business_record_id, created_by, created_at, updated_at)
                VALUES (:id, :tenant, 'FOLDER_INTAKE', :status, :record, :actor, CAST(:at AS timestamptz), now())
                """, new MapSqlParameterSource("id", id).addValue("tenant", tenant.value()).addValue("status", status)
                .addValue("record", record).addValue("actor", UUID.randomUUID()).addValue("at", at));
        return id;
    }

    private static UUID document(NamedParameterJdbcTemplate jdbc, TenantId tenant, UUID pkg, UUID id) {
        jdbc.update("""
                INSERT INTO documents (id, tenant_id, package_id, document_type, status, file_name, mime_type, size_bytes,
                                       checksum_algorithm, checksum_value, page_count, created_at, updated_at, version)
                VALUES (:id, :tenant, :pkg, 'OTHER', 'UPLOADED', 'a.pdf', 'application/pdf', 1, 'SHA-256', 'x', 1, now(), now(), 0)
                """, new MapSqlParameterSource("id", id).addValue("tenant", tenant.value()).addValue("pkg", pkg));
        return id;
    }
}
