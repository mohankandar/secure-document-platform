package com.securedoc.platform.infrastructure.erasure;

import com.documentplatform.eraselog.EraseEntry;
import com.documentplatform.eraselog.EraseHandler;
import com.documentplatform.eraselog.EraseRequest;
import com.documentplatform.eraselog.ErasedBy;
import com.documentplatform.eraselog.fields.CaseFieldsEraseHandler;
import com.securedoc.platform.application.casefields.CaseFieldService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Task 0061: the values of a business record are erased when no case needs them any more: every case of the record has
 * been purged by its retention, or was an incomplete intake that expired and was cleaned. A record no case ever came to
 * use (intake failed) goes after a day.
 */
public class CaseFieldErasure {
    private static final String UNUSED = """
              from fld_record r
             where r.product_code = '%s' and r.record_type = '%s'
               and not exists (
                    select 1
                      from document_packages p
                      left join package_lifecycles pl on pl.package_id = p.id and pl.tenant_id = p.tenant_id
                     where p.tenant_id = r.entity_uuid and p.business_record_id = cast(r.record_id as uuid)
                       and pl.purged_at is null
                       and not (p.status = 'CANCELLED' and exists (
                            select 1 from external_intakes e
                             where e.package_id = p.id and e.tenant_id = p.tenant_id
                               and e.status = 'INCOMPLETE_EXPIRED' and e.cleanup_completed_at is not null)))
               and (r.created_at < now() - interval '1 day' or exists (
                    select 1 from document_packages p
                     where p.tenant_id = r.entity_uuid and p.business_record_id = cast(r.record_id as uuid)))
            """.formatted(CaseFieldService.PRODUCT, CaseFieldService.RECORD_TYPE);

    private final JdbcTemplate jdbc;

    public CaseFieldErasure(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    public record Unused(TenantId tenantId, UUID recordId) {
    }

    public List<Unused> unused(int limit) {
        return jdbc.query("select r.entity_uuid, r.record_id" + UNUSED + " order by r.created_at, r.record_uuid limit ?",
                (rs, n) -> new Unused(TenantId.of(rs.getObject(1, UUID.class)), UUID.fromString(rs.getString(2))), limit);
    }

    public boolean stillUnused(Unused record) {
        return !jdbc.queryForList("select 1" + UNUSED + " and r.entity_uuid = ? and r.record_id = ?",
                Integer.class, record.tenantId().value(), record.recordId().toString()).isEmpty();
    }

    /** platform-core's own handler does the erasing; LexaVault adds its audit record, under the id in the entry. */
    public EraseHandler handler(CaseFieldsEraseHandler platform) {
        return new EraseHandler() {
            @Override
            public String kind() {
                return platform.kind();
            }

            @Override
            public void check(EraseRequest request) {
                platform.check(request);
            }

            @Override
            public Applied apply(EraseEntry entry, Context context) {
                Applied applied = platform.apply(entry, context);
                audit(entry);
                return applied;
            }

            @Override
            public Verified verify(EraseEntry entry) {
                return platform.verify(entry);
            }
        };
    }

    private void audit(EraseEntry entry) {
        EraseRequest request = entry.request();
        boolean person = request.by().type() == ErasedBy.Type.PERSON;
        UUID id = UUID.fromString(request.auditId());
        jdbc.update("""
                insert into audit_records (id, tenant_id, actor_id, action, resource_type, resource_id, outcome,
                        correlation_id, detail, occurred_at, created_at)
                select ?, t.id, ?, ?, 'BusinessRecord', ?, 'SUCCESS', ?, ?, ?, ?
                  from tenants t where t.id = ?
                on conflict (id) do nothing
                """, id, person ? UUID.fromString(request.by().id()) : new UUID(0, 0), AuditAction.CASE_FIELDS_ERASED.name(),
                request.recordId(), id, "Erased: kind=" + request.kind() + (person ? "" : ", rule=" + request.by().id())
                        + ", eraseLogEntry=" + entry.seq(),
                Timestamp.from(request.erasedAt()), Timestamp.from(Instant.now()), request.entity());
    }
}
