package com.securedoc.platform.infrastructure.intake;

import com.securedoc.platform.application.port.out.CaseBatchStore;
import com.securedoc.platform.domain.document.version.DocumentVersionRecognition;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Task 0104: tables case_batches and case_batch_files and the version columns of documents (migration 088). Every file
 * a case ever received stays here for the life of the case, also after its original is purged: the document row may be
 * gone, the fingerprint is not.
 */
@Repository
public class JdbcCaseBatchStore implements CaseBatchStore, com.securedoc.platform.application.port.out.CaseBatchScope {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcCaseBatchStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<DocumentVersionRecognition.Known> knownVersions(TenantId tenantId, PackageId packageId) {
        return jdbc.query("""
                select f.document_id, coalesce(d.lineage_id, f.document_id) lineage_id, coalesce(d.version_number, 1) version_number,
                       f.sha256, f.size_bytes, f.name_hash, d.pdf_id_hash, d.xmp_id_hash, d.source_document_id_hash
                from case_batch_files f left join documents d on d.id = f.document_id and d.tenant_id = f.tenant_id
                where f.tenant_id = :tenant and f.package_id = :pkg and f.document_id is not null
                  and f.outcome <> 'SKIPPED_IDENTICAL'
                order by f.received_at, f.id
                """, params(tenantId, packageId), (rs, i) -> new DocumentVersionRecognition.Known(
                rs.getObject("document_id", UUID.class), rs.getObject("lineage_id", UUID.class), rs.getInt("version_number"),
                rs.getString("sha256"), rs.getLong("size_bytes"), rs.getString("name_hash"), rs.getString("pdf_id_hash"),
                rs.getString("xmp_id_hash"), rs.getString("source_document_id_hash")));
    }

    @Override
    public java.util.Optional<PackageId> openCaseFor(TenantId tenantId, UUID businessRecordId) {
        if (businessRecordId == null) return java.util.Optional.empty();
        List<UUID> ids = jdbc.queryForList("""
                select p.id from document_packages p
                left join package_lifecycles l on l.package_id = p.id
                where p.tenant_id = :tenant and p.business_record_id = :record
                  and p.status not in ('REJECTED', 'CANCELLED') and l.purged_at is null
                order by p.created_at desc, p.id
                limit 1
                """, new MapSqlParameterSource("tenant", tenantId.value()).addValue("record", businessRecordId), UUID.class);
        return ids.stream().findFirst().map(PackageId::of);
    }

    @Override
    public Batch openBatch(TenantId tenantId, PackageId packageId, String source, Instant receivedAt) {
        adoptFirstBatch(tenantId, packageId);
        for (int attempt = 0; ; attempt++) {
            UUID id = UUID.randomUUID();
            try {
                Integer number = jdbc.queryForObject("""
                        insert into case_batches (id, tenant_id, package_id, batch_number, source, received_at, export_cycle, state)
                        select :id, :tenant, :pkg, coalesce(max(batch_number), 0) + 1, :source, :at, null, 'RECEIVED'
                        from case_batches where package_id = :pkg
                        returning batch_number
                        """, params(tenantId, packageId).addValue("id", id).addValue("source", source)
                        .addValue("at", Timestamp.from(receivedAt)), Integer.class);
                return new Batch(id, number);
            } catch (DuplicateKeyException race) {
                // Two batches for one case at once: the other took the number; take the next.
                if (attempt >= 4) throw race;
            }
        }
    }

    @Override
    public void recordFile(TenantId tenantId, PackageId packageId, UUID batchId, ReceivedFile file) {
        jdbc.update("""
                insert into case_batch_files (id, tenant_id, package_id, batch_id, sha256, size_bytes, page_count, name_hash,
                                              outcome, rule, document_id, received_at)
                values (:id, :tenant, :pkg, :batch, :sha, :size, :pages, :name, :outcome, :rule, :doc, clock_timestamp())
                """, params(tenantId, packageId).addValue("id", UUID.randomUUID()).addValue("batch", batchId)
                .addValue("sha", file.sha256().toLowerCase(java.util.Locale.ROOT)).addValue("size", file.sizeBytes())
                .addValue("pages", file.pageCount()).addValue("name", file.nameHash()).addValue("outcome", file.outcome().name())
                .addValue("rule", file.rule().name()).addValue("doc", file.documentId()));
    }

    @Override
    public void setVersion(TenantId tenantId, UUID documentId, UUID batchId, DocumentVersionRecognition.Result result,
                           String pdfIdHash, String xmpIdHash, String sourceDocumentIdHash) {
        int updated = jdbc.update("""
                update documents set batch_id = :batch, lineage_id = :lineage, version_number = :version,
                       replaces_document_id = :replaces, possible_version_of = :possible, pdf_id_hash = :pdf,
                       xmp_id_hash = :xmp, source_document_id_hash = :src
                where id = :doc and tenant_id = :tenant
                """, new MapSqlParameterSource("tenant", tenantId.value()).addValue("doc", documentId).addValue("batch", batchId)
                .addValue("lineage", result.lineageId() != null ? result.lineageId() : documentId)
                .addValue("version", Math.max(1, result.versionNumber())).addValue("replaces", result.replacesDocumentId())
                .addValue("possible", result.possibleVersionOf()).addValue("pdf", pdfIdHash).addValue("xmp", xmpIdHash)
                .addValue("src", sourceDocumentIdHash));
        if (updated != 1) throw new IllegalStateException("No such document of this entity");
    }

    @Override
    public int cyclesBeforeCurrentBatch(TenantId tenantId, PackageId packageId) {
        Integer cycles = jdbc.queryForObject("""
                select coalesce(max(b.export_cycle), 0) from case_batches b
                where b.tenant_id = :tenant and b.package_id = :pkg
                  and b.batch_number < (select max(c.batch_number) from case_batches c
                                        where c.tenant_id = :tenant and c.package_id = :pkg
                                          and c.state <> 'NOTHING_NEW')
                """, params(tenantId, packageId), Integer.class);
        return cycles == null ? 0 : cycles;
    }

    @Override
    public java.util.Optional<java.util.Set<UUID>> currentBatchDocuments(TenantId tenantId, PackageId packageId) {
        List<Current> current = current(tenantId, packageId);
        if (current.isEmpty() || current.getFirst().number() <= 1) return java.util.Optional.empty();
        return java.util.Optional.of(java.util.Set.copyOf(jdbc.queryForList("""
                select d.id from documents d
                where d.tenant_id = :tenant and d.package_id = :pkg and d.batch_id = :batch
                """, params(tenantId, packageId).addValue("batch", current.getFirst().id()), UUID.class)));
    }

    @Override
    public void currentBatchDelivered(TenantId tenantId, PackageId packageId, int exportCycle) {
        List<Current> current = current(tenantId, packageId);
        if (current.isEmpty()) return; // a case from before batches, or from an intake not yet joined to batches
        jdbc.update("update case_batches set export_cycle = :cycle, state = 'DELIVERED' where id = :id and tenant_id = :tenant",
                new MapSqlParameterSource("tenant", tenantId.value()).addValue("id", current.getFirst().id())
                        .addValue("cycle", exportCycle));
    }

    /**
     * A case made without batches: its documents become batch 1 (as migration 088 did for the cases before it), with the
     * cycles that delivered it, so the next batch is compared against them, numbered 2 and counted from there. Runs once:
     * nothing happens when the case has a batch.
     */
    private void adoptFirstBatch(TenantId tenantId, PackageId packageId) {
        MapSqlParameterSource p = params(tenantId, packageId);
        int adopted = jdbc.update("""
                insert into case_batches (id, tenant_id, package_id, batch_number, source, received_at, export_cycle, state)
                select p.id, p.tenant_id, p.id, 1, 'FIRST', p.created_at, nullif(coalesce(l.successful_export_cycles, 0), 0),
                       case when coalesce(l.successful_export_cycles, 0) > 0 then 'DELIVERED' else 'RECEIVED' end
                from document_packages p
                left join package_lifecycles l on l.package_id = p.id and l.tenant_id = p.tenant_id
                where p.id = :pkg and p.tenant_id = :tenant
                  and not exists (select 1 from case_batches b where b.package_id = :pkg)
                  and exists (select 1 from documents d where d.package_id = :pkg and d.tenant_id = :tenant)
                on conflict do nothing
                """, p);
        if (adopted == 0) return;
        jdbc.update("""
                update documents set batch_id = :pkg, lineage_id = coalesce(lineage_id, id)
                where package_id = :pkg and tenant_id = :tenant and batch_id is null
                """, p);
        jdbc.update("""
                insert into case_batch_files (id, tenant_id, package_id, batch_id, sha256, size_bytes, page_count, name_hash,
                                              outcome, rule, document_id, received_at)
                select d.id, d.tenant_id, d.package_id, :pkg, lower(d.checksum_value), coalesce(d.size_bytes, 0), d.page_count,
                       null, 'NEW_DOCUMENT', 'NONE', d.id, d.created_at
                from documents d
                where d.package_id = :pkg and d.tenant_id = :tenant and d.checksum_value is not null
                  and not exists (select 1 from case_batch_files f where f.document_id = d.id)
                """, p);
    }

    @Override
    public java.util.Optional<BatchState> latestBatch(TenantId tenantId, PackageId packageId) {
        return jdbc.query("""
                select id, batch_number, state from case_batches where tenant_id = :tenant and package_id = :pkg
                order by batch_number desc limit 1
                """, params(tenantId, packageId), (rs, i) -> new BatchState(rs.getObject("id", UUID.class),
                rs.getInt("batch_number"), rs.getString("state"))).stream().findFirst();
    }

    @Override
    public java.util.Set<String> recordedFiles(TenantId tenantId, UUID batchId) {
        return java.util.Set.copyOf(jdbc.queryForList(
                "select sha256 from case_batch_files where tenant_id = :tenant and batch_id = :batch",
                new MapSqlParameterSource("tenant", tenantId.value()).addValue("batch", batchId), String.class));
    }

    @Override
    public void markNothingNew(TenantId tenantId, UUID batchId) {
        jdbc.update("update case_batches set state = 'NOTHING_NEW' where id = :id and tenant_id = :tenant",
                new MapSqlParameterSource("tenant", tenantId.value()).addValue("id", batchId));
    }

    private record Current(UUID id, int number) {}

    private List<Current> current(TenantId tenantId, PackageId packageId) {
        return jdbc.query("""
                select id, batch_number from case_batches where tenant_id = :tenant and package_id = :pkg
                  and state <> 'NOTHING_NEW'
                order by batch_number desc limit 1
                """, params(tenantId, packageId), (rs, i) -> new Current(rs.getObject("id", UUID.class), rs.getInt("batch_number")));
    }

    private static MapSqlParameterSource params(TenantId tenantId, PackageId packageId) {
        return new MapSqlParameterSource("tenant", tenantId.value()).addValue("pkg", packageId.value());
    }
}
