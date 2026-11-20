package com.securedoc.platform.infrastructure.business;

import com.securedoc.platform.application.port.out.PolicyEstimateReadPort;
import com.securedoc.platform.domain.identity.TenantId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL read adapter for the policy estimate (task 0019 B2, B3). One aggregate query, tenant-scoped on every
 * table it reads; no entity is loaded. It reads the finding's confidence, bounding-box columns and detector name
 * only: never found text, offsets, files, file names or business identifiers.
 */
@Component
public class JpaPolicyEstimateReadAdapter implements PolicyEstimateReadPort {

    /** Findings a person drew by hand ({@code ManualFallbackService.MANUAL_DETECTOR}); they don't depend on the policy. */
    static final String MANUAL_DETECTOR = "MANUAL_FALLBACK";

    /**
     * Rows: ('C', null, cases in the window), ('F', effective confidence, findings), ('L', lowest effective
     * confidence of a case, cases). Located means all four bounding-box columns are set, as the finding mapper reads it.
     */
    static final String SQL = """
            with win as (
                select p.id from document_packages p
                 where p.tenant_id = :tenant and p.created_at >= :since and p.created_at < :until
            ), f as (
                select d.package_id,
                       case when fi.bbox_left is not null and fi.bbox_top is not null
                                 and fi.bbox_width is not null and fi.bbox_height is not null
                            then fi.confidence else -1.0 end as eff
                  from win
                  join documents d on d.package_id = win.id and d.tenant_id = :tenant
                  join findings fi on fi.document_id = d.id and fi.tenant_id = :tenant
                 where fi.detector_name <> :manual
            )
            select 'C' as kind, cast(null as double precision) as v, count(*) as n from win
            union all
            select 'F', eff, count(*) from f group by eff
            union all
            select 'L', lowest, count(*) from (select min(eff) as lowest from f group by package_id) c group by lowest
            """;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional(readOnly = true)
    public Facts facts(TenantId tenantId, Instant since, Instant until) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(SQL)
                .setParameter("tenant", tenantId.value())
                .setParameter("since", since)
                .setParameter("until", until)
                .setParameter("manual", MANUAL_DETECTOR)
                .getResultList();
        long cases = 0;
        List<Bucket> findings = new ArrayList<>();
        List<Bucket> caseLowest = new ArrayList<>();
        for (Object[] row : rows) {
            String kind = String.valueOf(row[0]);
            long n = ((Number) row[2]).longValue();
            switch (kind) {
                case "C" -> cases = n;
                case "F" -> findings.add(new Bucket(((Number) row[1]).doubleValue(), n));
                case "L" -> caseLowest.add(new Bucket(((Number) row[1]).doubleValue(), n));
                default -> throw new IllegalStateException("unexpected estimate row");
            }
        }
        return new Facts(cases, findings, caseLowest);
    }
}
