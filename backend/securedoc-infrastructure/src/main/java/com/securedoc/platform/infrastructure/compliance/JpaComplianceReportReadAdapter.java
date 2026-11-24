package com.securedoc.platform.infrastructure.compliance;

import com.securedoc.platform.infrastructure.operations.ReportingCaseRules;
import com.securedoc.platform.application.audit.query.AuditRecordSummary;
import com.securedoc.platform.application.port.out.ComplianceReportReadPort;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.persistence.entity.AuditRecordEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * PostgreSQL/JPA read adapter for the compliance report (task 0017 B2). Every query is tenant-scoped and reads
 * structured columns only: counts, times, ids and audit records. The audit detail is returned as recorded; the
 * report decides what of it is printed ({@code AppendixDetailAllowList}).
 */
@Component
public class JpaComplianceReportReadAdapter implements ComplianceReportReadPort {

    static final int PAGE_SIZE = 1000;
    private static final String SYSTEM_ACTOR = "00000000-0000-0000-0000-000000000000";

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Optional<AuditRecordSummary> latestSuccess(TenantId tenantId, String action, Instant before) {
        return entityManager.createQuery("""
                        select a from AuditRecordEntity a
                         where a.tenantId=:tenant and a.action=:action and a.outcome='SUCCESS' and a.occurredAt<:before
                         order by a.occurredAt desc, a.id desc""", AuditRecordEntity.class)
                .setParameter("tenant", tenantId.value()).setParameter("action", action).setParameter("before", before)
                .setMaxResults(1).getResultList().stream().findFirst().map(JpaComplianceReportReadAdapter::summary);
    }

    @Override
    public List<AuditRecordSummary> successes(TenantId tenantId, String action, Instant start, Instant endExclusive) {
        return entityManager.createQuery("""
                        select a from AuditRecordEntity a
                         where a.tenantId=:tenant and a.action=:action and a.outcome='SUCCESS'
                           and a.occurredAt>=:start and a.occurredAt<:end
                         order by a.occurredAt asc, a.id asc""", AuditRecordEntity.class)
                .setParameter("tenant", tenantId.value()).setParameter("action", action)
                .setParameter("start", start).setParameter("end", endExclusive)
                .getResultList().stream().map(JpaComplianceReportReadAdapter::summary).toList();
    }

    @Override
    public long count(TenantId tenantId, ActivityQuery query, Instant start, Instant endExclusive) {
        StringBuilder jpql = new StringBuilder("""
                select count(a) from AuditRecordEntity a
                 where a.tenantId=:tenant and a.occurredAt>=:start and a.occurredAt<:end""");
        if (!query.actions().isEmpty()) jpql.append(" and a.action in :actions");
        if (!query.outcomes().isEmpty()) jpql.append(" and a.outcome in :outcomes");
        if (query.excludeSystemActor()) jpql.append(" and a.actorId<>:system");
        if (query.resourceId() != null) jpql.append(" and a.resourceId=:resourceId");
        TypedQuery<Long> q = entityManager.createQuery(jpql.toString(), Long.class)
                .setParameter("tenant", tenantId.value()).setParameter("start", start).setParameter("end", endExclusive);
        if (!query.actions().isEmpty()) q.setParameter("actions", query.actions());
        if (!query.outcomes().isEmpty()) q.setParameter("outcomes", query.outcomes());
        if (query.excludeSystemActor()) q.setParameter("system", UUID.fromString(SYSTEM_ACTOR));
        if (query.resourceId() != null) q.setParameter("resourceId", query.resourceId());
        return q.getSingleResult();
    }

    @Override
    public void forEachRecord(TenantId tenantId, Instant start, Instant endExclusive,
                              Consumer<AuditRecordSummary> consumer) {
        // The period is in the past, so its records no longer change; ordered paging by (time, id) is stable.
        for (int offset = 0; ; offset += PAGE_SIZE) {
            List<AuditRecordEntity> page = entityManager.createQuery("""
                            select a from AuditRecordEntity a
                             where a.tenantId=:tenant and a.occurredAt>=:start and a.occurredAt<:end
                             order by a.occurredAt asc, a.id asc""", AuditRecordEntity.class)
                    .setParameter("tenant", tenantId.value()).setParameter("start", start)
                    .setParameter("end", endExclusive)
                    .setFirstResult(offset).setMaxResults(PAGE_SIZE).getResultList();
            for (AuditRecordEntity e : page) {
                consumer.accept(summary(e));
                entityManager.detach(e); // read-only: keep the persistence context small, touch nothing else
            }
            if (page.size() < PAGE_SIZE) return;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public PageReading pageReading(TenantId tenantId, Instant start, Instant endExclusive) {
        String records = """
                 from audit_records a
                where a.tenant_id=:tenant and a.action='DOCUMENT_DETECTION_COMPLETED' and a.outcome='SUCCESS'
                  and a.occurred_at>=:start and a.occurred_at<:end
                """;
        Object[] pages = row(entityManager.createNativeQuery("select "
                        + sumOf("textPages") + ", " + sumOf("signaturePages") + ", " + sumOf("visionPages") + ", "
                        + sumOf("visionPagesToPerson") + records)
                .setParameter("tenant", tenantId.value()).setParameter("start", start)
                .setParameter("end", endExclusive).getSingleResult());
        List<Object[]> models = entityManager.createNativeQuery("select m.model, count(*) from (select "
                        + "unnest(string_to_array(substring(a.detail from 'aiModels=[[]([^]]*)[]]'), ';')) as model"
                        + records + ") m group by m.model order by m.model")
                .setParameter("tenant", tenantId.value()).setParameter("start", start)
                .setParameter("end", endExclusive).getResultList();
        return new PageReading(number(pages[0]), number(pages[1]), number(pages[2]), number(pages[3]),
                models.stream().map(m -> new ModelUse((String) m[0], number(m[1]))).toList());
    }

    private static String sumOf(String key) {
        return "coalesce(sum(cast(substring(a.detail from '[, ]" + key + "=([0-9]+)') as bigint)),0)";
    }

    @Override
    public RetentionCounts retention(TenantId tenantId, Instant start, Instant endExclusive) {
        Object[] due = row(entityManager.createNativeQuery("""
                with due as (
                    select pl.package_id,
                           case when pl.successful_export_cycles > 0 then pl.original_delete_at
                                else pl.technical_hold_until end as due_at
                      from package_lifecycles pl
                     where pl.tenant_id=:tenant)
                select count(*),
                       coalesce(sum(case when exists (
                           select 1 from audit_records a
                            where a.tenant_id=:tenant and a.action='ORIGINAL_ARTIFACT_PURGED' and a.outcome='SUCCESS'
                              and a.resource_type='DocumentPackage' and a.resource_id=cast(due.package_id as text)
                              and a.occurred_at <= due.due_at + interval '24 hours') then 1 else 0 end),0)
                  from due
                 where due.due_at>=:start and due.due_at<:end
                """).setParameter("tenant", tenantId.value()).setParameter("start", start)
                .setParameter("end", endExclusive).getSingleResult());
        Object still = entityManager.createNativeQuery("""
                select count(*) from package_lifecycles pl
                 where pl.tenant_id=:tenant and pl.successful_export_cycles > 0
                   and pl.last_exported_at < :end and pl.original_delete_at >= :end
                   and (pl.original_purged_at is null or pl.original_purged_at >= :end)
                """).setParameter("tenant", tenantId.value()).setParameter("end", endExclusive).getSingleResult();
        return new RetentionCounts(number(due[0]), number(due[1]), number(still));
    }

    /** "Finished by hand": delivered in the period, by the shared rule in {@link ReportingCaseRules}. */
    static final String FINISHED_BY_HAND_SQL = "select count(*) from reporting_package_facts f"
            + " where f.tenant_id=:tenant and f.first_delivered_at>=:start and f.first_delivered_at<:end"
            + " and " + ReportingCaseRules.FINISHED_BY_HAND;

    @Override
    public CaseCounts cases(TenantId tenantId, Instant start, Instant endExclusive) {
        Object[] received = row(entityManager.createNativeQuery("""
                select coalesce(sum(case when f.status='REJECTED' then 1 else 0 end),0),
                       coalesce(sum(case when (f.first_delivered_at is null or f.first_delivered_at>=:end)
                                          and f.status not in ('REJECTED','CANCELLED') then 1 else 0 end),0)
                  from reporting_package_facts f
                 where f.tenant_id=:tenant and f.received_at>=:start and f.received_at<:end
                """).setParameter("tenant", tenantId.value()).setParameter("start", start)
                .setParameter("end", endExclusive).getSingleResult());
        Object byHand = entityManager.createNativeQuery(FINISHED_BY_HAND_SQL).setParameter("tenant", tenantId.value()).setParameter("start", start)
                .setParameter("end", endExclusive).getSingleResult();
        return new CaseCounts(number(received[0]), number(received[1]), number(byHand));
    }

    private static AuditRecordSummary summary(AuditRecordEntity e) {
        return new AuditRecordSummary(e.getId().toString(), e.getActorId().toString(), e.getAction(),
                e.getResourceType(), e.getResourceId(), e.getOutcome(), e.getCorrelationId().toString(),
                e.getDetail(), e.getOccurredAt(), e.getActorDisplayName(), e.getCaseId());
    }

    private static Object[] row(Object value) {
        return value instanceof Object[] values ? values : new Object[]{value};
    }

    private static long number(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }
}
