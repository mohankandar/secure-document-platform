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
    void theOpenCaseOfAContractIsTheNewestNotRejectedCancelledOrErased() throws Exception {
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
            UUID erased = pkg(jdbc, tenant, record, "COMPLETED", "2026-11-04T10:00:00Z");
            jdbc.update("INSERT INTO package_lifecycles (package_id, tenant_id, successful_export_cycles, purged_at, updated_at) VALUES (:p, :t, 1, now(), now())",
                    new MapSqlParameterSource("p", erased).addValue("t", tenant.value()));
            assertEquals(delivered, store.openCaseFor(tenant, record).orElseThrow().value(),
                    "a rejected, cancelled or erased case takes no batch");

            pkg(jdbc, tenant, UUID.randomUUID(), "COMPLETED", "2026-11-05T10:00:00Z");
            pkg(jdbc, other, record, "COMPLETED", "2026-11-06T10:00:00Z");
            assertEquals(delivered, store.openCaseFor(tenant, record).orElseThrow().value(), "never another contract or entity");
            assertTrue(store.openCaseFor(tenant, null).isEmpty());
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
