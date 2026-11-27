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
public class JdbcCaseBatchStore implements CaseBatchStore {

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
    public Batch openBatch(TenantId tenantId, PackageId packageId, String source, Instant receivedAt) {
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

    private static MapSqlParameterSource params(TenantId tenantId, PackageId packageId) {
        return new MapSqlParameterSource("tenant", tenantId.value()).addValue("pkg", packageId.value());
    }
}
