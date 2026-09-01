package com.securedoc.platform.infrastructure.operations;

import com.securedoc.platform.application.operations.model.DashboardColumnDefinition;
import com.securedoc.platform.application.operations.model.DashboardIdentifierValue;
import com.securedoc.platform.application.operations.model.DashboardItem;
import com.securedoc.platform.application.operations.model.DashboardOverview;
import com.securedoc.platform.application.operations.model.DashboardPage;
import com.securedoc.platform.application.operations.model.DashboardStage;
import com.securedoc.platform.application.operations.model.OperationalReportSnapshot;
import com.securedoc.platform.application.port.out.OperationsReadPort;
import com.securedoc.platform.domain.identity.TenantId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * PostgreSQL/JPA read adapter for the Unit 23 shared dashboard and compact operational reports.
 * It intentionally queries structured operational tables rather than parsing audit/log text.
 */
@Component
public class JpaOperationsReadAdapter implements OperationsReadPort {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public DashboardPage loadDashboard(TenantId tenantId, DashboardStage stage, String search,
                                       int offset, int limit, String sortField, boolean descending) {
        UUID tenant = tenantId.value();
        DashboardOverview overview = loadOverview(tenant, true);
        List<DashboardColumnDefinition> columns = loadColumns(tenant);

        String where = " p.tenant_id = :tenant and p.dashboard_hidden_at is null " + stageClause(stage)
                + (search == null || search.isBlank() ? "" : searchClause());

        Query countQuery = entityManager.createNativeQuery("select count(*) from document_packages p where " + where);
        bindCommon(countQuery, tenant, search);
        long totalItems = number(countQuery.getSingleResult());

        String sql = """
                select p.id, p.business_record_id, p.package_type, p.status,
                       (select count(*) from documents d where d.package_id=p.id and d.tenant_id=p.tenant_id) as document_count,
                       coalesce(pl.successful_export_cycles,0), pl.last_exported_at,
                       pl.original_delete_at, pl.technical_hold_until,
                       p.created_at, p.updated_at
                  from document_packages p
                  left join package_lifecycles pl on pl.package_id=p.id and pl.tenant_id=p.tenant_id
                 where %s
                 order by %s %s, p.id desc
                """.formatted(where, orderExpression(sortField), descending ? "desc" : "asc");

        Query query = entityManager.createNativeQuery(sql);
        bindCommon(query, tenant, search);
        query.setFirstResult(offset);
        query.setMaxResults(limit);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        Map<UUID, List<DashboardIdentifierValue>> identifiers = loadIdentifiers(tenant, rows, columns);
        List<DashboardItem> items = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            UUID packageId = uuid(row[0]);
            UUID businessRecordId = row[1] == null ? null : uuid(row[1]);
            String status = string(row[3]);
            items.add(new DashboardItem(
                    packageId,
                    string(row[2]),
                    status,
                    mapStage(status),
                    attention(status),
                    businessRecordId == null ? List.of() : identifiers.getOrDefault(businessRecordId, List.of()),
                    integer(row[4]),
                    integer(row[5]),
                    instant(row[6]),
                    instant(row[7]),
                    instant(row[8]),
                    instant(row[9]),
                    instant(row[10])
            ));
        }
        return new DashboardPage(overview, columns, items, totalItems, offset, limit);
    }

    @Override
    public OperationalReportSnapshot loadReports(TenantId tenantId, Instant now, Instant from, Instant to,
                                                  String source, String outcome) {
        UUID tenant = tenantId.value();
        DashboardOverview overview = loadReportOverview(tenant, source, outcome);
        long seconds = Math.max(1L, to.getEpochSecond() - from.getEpochSecond());
        Instant previousFrom = from.minusSeconds(seconds);
        String sourceClause = sourceClause("p", source);
        String outcomeClause = outcomeClause("p", outcome);

        Object[] periodVolumes = periodVolumes(tenant, from, to, source, outcome);
        Object[] previousVolumes = periodVolumes(tenant, previousFrom, from, source, outcome);

        Query durationQuery = entityManager.createNativeQuery("""
                select extract(epoch from (min(e.exported_at)-p.created_at))/3600.0
                  from document_packages p
                  join package_export_records e on e.package_id=p.id and e.tenant_id=p.tenant_id
                 where p.tenant_id=:tenant and p.created_at>=:from and p.created_at<:to%s%s
                 group by p.id,p.created_at
                """.formatted(sourceClause, outcomeClause));
        bindReport(durationQuery, tenant, from, to, source);
        List<?> durationRows = durationQuery.getResultList();
        List<Double> durations = durationRows.stream().map(r -> ((Number) row(r)[0]).doubleValue()).sorted().toList();

        Query automationQuery = entityManager.createNativeQuery("""
                select count(distinct e.package_id),
                       count(distinct case when exists (
                         select 1 from review_tasks rt where rt.tenant_id=p.tenant_id
                          and rt.package_id=p.id
                       ) then e.package_id end)
                  from package_export_records e
                  join document_packages p on p.id=e.package_id and p.tenant_id=e.tenant_id
                 where e.tenant_id=:tenant and e.exported_at>=:from and e.exported_at<:to%s%s
                """.formatted(sourceClause, outcomeClause));
        bindReport(automationQuery, tenant, from, to, source);
        Object[] automation = row(automationQuery.getSingleResult());

        Query backlogQuery = entityManager.createNativeQuery("""
                select count(*),
                       coalesce(sum(case when status in ('PENDING_REVIEW','IN_REVIEW','PENDING_FINAL_APPROVAL','FAILED') then 1 else 0 end),0),
                       coalesce(extract(epoch from (:now-min(created_at)))/3600.0,0)
                  from document_packages p
                 where p.tenant_id=:tenant and p.status not in ('COMPLETED','REJECTED','CANCELLED')%s%s
                """.formatted(sourceClause, outcomeClause));
        bindCurrent(backlogQuery, tenant, now, source);
        Object[] backlog = row(backlogQuery.getSingleResult());

        Query channelsQuery = entityManager.createNativeQuery("""
                select count(*),
                       coalesce(sum(case when package_type='MANUAL_UPLOAD' then 1 else 0 end),0),
                       coalesce(sum(case when package_type='API_SUBMISSION' then 1 else 0 end),0),
                       coalesce(sum(case when package_type='FOLDER_INTAKE' then 1 else 0 end),0)
                  from document_packages p where tenant_id=:tenant and p.created_at>=:from and p.created_at<:to%s%s
                """.formatted(sourceClause, outcomeClause));
        bindReport(channelsQuery, tenant, from, to, source);
        Object[] channels = row(channelsQuery.getSingleResult());

        Object[] observations = row(entityManager.createNativeQuery("""
                select
                  (select count(*) from (
                     select distinct on (source_reference) status, visible_after
                       from intake_observations
                      where tenant_id=:tenant
                      order by source_reference, updated_at desc
                   ) latest where latest.status='WAITING_FOR_TRIGGER' and latest.visible_after <= :now),
                  (select count(*) from intake_observations where tenant_id=:tenant and status='DUPLICATE'),
                  (select count(*) from intake_observations where tenant_id=:tenant and status='REJECTED')
                """).setParameter("tenant", tenant).setParameter("now", now).getSingleResult());

        Query exportQuery = entityManager.createNativeQuery("""
                select count(distinct e.package_id), count(*)
                  from package_export_records e
                  join document_packages p on p.id=e.package_id and p.tenant_id=e.tenant_id
                 where e.tenant_id=:tenant%s%s
                """.formatted(sourceClause, outcomeClause));
        bindTenantAndSource(exportQuery, tenant, source);
        Object[] export = row(exportQuery.getSingleResult());

        Instant due = now.plusSeconds(24L * 60L * 60L);
        Query retentionQuery = entityManager.createNativeQuery("""
                select
                  coalesce(sum(case when pl.original_purged_at is null and pl.original_delete_at > :now and pl.original_delete_at <= :due then 1 else 0 end),0),
                  coalesce(sum(case when pl.redacted_purged_at is null and pl.redacted_delete_at > :now and pl.redacted_delete_at <= :due then 1 else 0 end),0),
                  coalesce(sum(case when pl.purged_at is null and pl.successful_export_cycles=0 and pl.technical_hold_until > :now and pl.technical_hold_until <= :due then 1 else 0 end),0),
                  coalesce(sum(case when pl.purged_at is not null then 1 else 0 end),0),
                  coalesce(sum(case when pl.original_purged_at is null and pl.original_delete_at is not null and pl.original_delete_at < :now then 1 else 0 end),0),
                  coalesce(sum(case when pl.redacted_purged_at is null and pl.redacted_delete_at is not null and pl.redacted_delete_at < :now then 1 else 0 end),0)
                  from package_lifecycles pl
                  join document_packages p on p.id=pl.package_id and p.tenant_id=pl.tenant_id
                 where pl.tenant_id=:tenant%s%s
                """.formatted(sourceClause, outcomeClause));
        bindTenantAndSource(retentionQuery, tenant, source);
        retentionQuery.setParameter("due", due).setParameter("now", now);
        Object[] retention = row(retentionQuery.getSingleResult());

        Query trendQuery = entityManager.createNativeQuery("""
                select day,
                       (select count(*) from document_packages p where p.tenant_id=:tenant
                         and p.created_at>=day and p.created_at<day+interval '1 day'%s%s),
                       (select count(distinct e.package_id) from package_export_records e
                         join document_packages p on p.id=e.package_id and p.tenant_id=e.tenant_id
                        where e.tenant_id=:tenant and e.exported_at>=day and e.exported_at<day+interval '1 day'%s%s)
                  from generate_series(date_trunc('day',cast(:from as timestamptz)),
                       date_trunc('day',cast(:to as timestamptz)-interval '1 second'), interval '1 day') day
                 order by day
                """.formatted(sourceClause, outcomeClause, sourceClause, outcomeClause));
        bindReport(trendQuery, tenant, from, to, source);
        @SuppressWarnings("unchecked")
        List<Object[]> trendRows = trendQuery.getResultList();
        List<OperationalReportSnapshot.TrendPoint> trend = trendRows.stream().map(r ->
                new OperationalReportSnapshot.TrendPoint(instant(r[0]), number(r[1]), number(r[2]))).toList();

        long batches = number(periodVolumes[0]);
        long completed = number(periodVolumes[3]);
        long delivered = number(periodVolumes[4]);
        long reviewed = number(automation[1]);

        return new OperationalReportSnapshot(
                now,
                new OperationalReportSnapshot.Period(from, to, previousFrom, from, source, outcome),
                new OperationalReportSnapshot.Executive(
                        batches, number(previousVolumes[0]), number(periodVolumes[1]), number(periodVolumes[2]),
                        completed, number(previousVolumes[3]), delivered, number(previousVolumes[4]),
                        percent(completed, batches), percent(delivered, batches), percentile(durations, .50),
                        percentile(durations, .95), percent(number(automation[0]) - reviewed, number(automation[0])),
                        percent(reviewed, number(automation[0])), number(backlog[0]), number(backlog[1]),
                        ((Number) backlog[2]).doubleValue(), trend),
                new OperationalReportSnapshot.Intake(
                        number(channels[0]), number(channels[1]), number(channels[2]), number(channels[3]),
                        includeObservations(source, outcome) ? number(observations[0]) : 0,
                        includeObservations(source, outcome) ? number(observations[1]) : 0,
                        includeObservations(source, outcome) ? number(observations[2]) : 0),
                new OperationalReportSnapshot.Processing(
                        overview.processing(), overview.needsReview(), overview.needsApproval(), overview.exported(),
                        overview.technicalAttention(), overview.rejected()),
                new OperationalReportSnapshot.Export(number(export[0]), number(export[1]), overview.needsApproval()),
                new OperationalReportSnapshot.Retention(
                        number(retention[0]), number(retention[1]), number(retention[2]), number(retention[3]),
                        number(retention[4]), number(retention[5]))
        );
    }

    private Object[] periodVolumes(UUID tenant, Instant from, Instant to, String source, String outcome) {
        String documentFilter = sourceClause("dp", source) + outcomeClause("dp", outcome);
        String exportFilter = sourceClause("ep", source) + outcomeClause("ep", outcome);
        String packageFilter = sourceClause("p", source) + outcomeClause("p", outcome);
        Query query = entityManager.createNativeQuery("""
                select count(*),
                       (select count(*) from documents d join document_packages dp on dp.id=d.package_id and dp.tenant_id=d.tenant_id
                         where d.tenant_id=:tenant and dp.created_at>=:from and dp.created_at<:to%s),
                       (select coalesce(sum(d.page_count),0) from documents d join document_packages dp on dp.id=d.package_id and dp.tenant_id=d.tenant_id
                         where d.tenant_id=:tenant and dp.created_at>=:from and dp.created_at<:to%s),
                       coalesce(sum(case when p.status='COMPLETED' then 1 else 0 end),0),
                       (select count(distinct e.package_id) from package_export_records e join document_packages ep on ep.id=e.package_id and ep.tenant_id=e.tenant_id
                         where e.tenant_id=:tenant and e.exported_at>=:from and e.exported_at<:to%s)
                  from document_packages p where p.tenant_id=:tenant and p.created_at>=:from and p.created_at<:to%s
                """.formatted(documentFilter, documentFilter, exportFilter, packageFilter));
        query.setParameter("tenant", tenant).setParameter("from", from).setParameter("to", to);
        if (!"ALL".equals(source)) query.setParameter("source", source);
        return row(query.getSingleResult());
    }

    private static void bindReport(Query query, UUID tenant, Instant from, Instant to, String source) {
        query.setParameter("tenant", tenant).setParameter("from", from).setParameter("to", to);
        if (!"ALL".equals(source)) query.setParameter("source", source);
    }

    private static void bindCurrent(Query query, UUID tenant, Instant now, String source) {
        bindTenantAndSource(query, tenant, source);
        query.setParameter("now", now);
    }

    private static void bindTenantAndSource(Query query, UUID tenant, String source) {
        query.setParameter("tenant", tenant);
        if (!"ALL".equals(source)) query.setParameter("source", source);
    }

    private static String sourceClause(String alias, String source) {
        return "ALL".equals(source) ? "" : " and " + alias + ".package_type=:source";
    }

    private static String outcomeClause(String alias, String outcome) {
        return switch (outcome) {
            case "PROCESSING" -> " and " + alias + ".status in ('CREATED','SUBMITTED','PROCESSING','APPROVED','VAULTED')";
            case "NEEDS_REVIEW" -> " and " + alias + ".status in ('PENDING_REVIEW','IN_REVIEW')";
            case "NEEDS_APPROVAL" -> " and " + alias + ".status='PENDING_FINAL_APPROVAL'";
            case "COMPLETED" -> " and " + alias + ".status='COMPLETED'";
            case "TECHNICAL_ATTENTION" -> " and " + alias + ".status='FAILED'";
            case "REJECTED" -> " and " + alias + ".status in ('REJECTED','CANCELLED')";
            default -> "";
        };
    }

    private static boolean includeObservations(String source, String outcome) {
        return "ALL".equals(outcome) && ("ALL".equals(source) || "FOLDER_INTAKE".equals(source));
    }

    private static double percent(long numerator, long denominator) {
        return denominator <= 0 ? 0 : Math.round((numerator * 1000.0 / denominator)) / 10.0;
    }

    private static double percentile(List<Double> values, double percentile) {
        if (values.isEmpty()) return 0;
        int index = (int) Math.ceil(percentile * values.size()) - 1;
        return Math.round(values.get(Math.max(0, Math.min(index, values.size() - 1))) * 10.0) / 10.0;
    }

    private DashboardOverview loadOverview(UUID tenant, boolean dashboardVisibleOnly) {
        String visibilityClause = dashboardVisibleOnly ? " and dashboard_hidden_at is null" : "";
        Object[] counts = row(entityManager.createNativeQuery("""
                select count(*),
                       coalesce(sum(case when status in ('CREATED','SUBMITTED','PROCESSING','APPROVED','VAULTED') then 1 else 0 end),0),
                       coalesce(sum(case when status in ('PENDING_REVIEW','IN_REVIEW') then 1 else 0 end),0),
                       coalesce(sum(case when status='PENDING_FINAL_APPROVAL' then 1 else 0 end),0),
                       coalesce(sum(case when status='COMPLETED' then 1 else 0 end),0),
                       coalesce(sum(case when status='FAILED' then 1 else 0 end),0),
                       coalesce(sum(case when status in ('REJECTED','CANCELLED') then 1 else 0 end),0)
                  from document_packages where tenant_id=:tenant%s
                """.formatted(visibilityClause)).setParameter("tenant", tenant).getSingleResult());
        return new DashboardOverview(number(counts[0]), number(counts[1]), number(counts[2]), number(counts[3]),
                number(counts[4]), number(counts[5]), number(counts[6]));
    }

    private DashboardOverview loadReportOverview(UUID tenant, String source, String outcome) {
        String filter = sourceClause("p", source) + outcomeClause("p", outcome);
        Query query = entityManager.createNativeQuery("""
                select count(*),
                       coalesce(sum(case when p.status in ('CREATED','SUBMITTED','PROCESSING','APPROVED','VAULTED') then 1 else 0 end),0),
                       coalesce(sum(case when p.status in ('PENDING_REVIEW','IN_REVIEW') then 1 else 0 end),0),
                       coalesce(sum(case when p.status='PENDING_FINAL_APPROVAL' then 1 else 0 end),0),
                       coalesce(sum(case when p.status='COMPLETED' then 1 else 0 end),0),
                       coalesce(sum(case when p.status='FAILED' then 1 else 0 end),0),
                       coalesce(sum(case when p.status in ('REJECTED','CANCELLED') then 1 else 0 end),0)
                  from document_packages p
                 where p.tenant_id=:tenant%s
                """.formatted(filter));
        bindTenantAndSource(query, tenant, source);
        Object[] counts = row(query.getSingleResult());
        return new DashboardOverview(number(counts[0]), number(counts[1]), number(counts[2]), number(counts[3]),
                number(counts[4]), number(counts[5]), number(counts[6]));
    }

    private List<DashboardColumnDefinition> loadColumns(UUID tenant) {
        Map<String, ColumnPolicy> policies = loadColumnPolicies(tenant);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                select identifier_key,label,primary_flag,searchable_flag,display_order
                  from business_identifier_definitions
                 where tenant_id=:tenant and active_flag=true and displayable_flag=true
                 order by display_order asc, label asc
                """).setParameter("tenant", tenant).getResultList();
        List<DashboardColumnDefinition> result = new ArrayList<>();
        int fallbackOrder = 0;
        for (Object[] row : rows) {
            String key = "identifier:" + string(row[0]);
            boolean primary = bool(row[2]);
            ColumnPolicy policy = policies.get(key);
            boolean allowed = primary || policy == null || policy.allowed();
            boolean defaultVisible = primary || policy == null || policy.defaultVisible();
            int order = policy == null ? fallbackOrder : policy.displayOrder();
            if (allowed) {
                result.add(new DashboardColumnDefinition(
                        key, string(row[1]), "IDENTIFIER", primary, bool(row[3]), defaultVisible, order));
            }
            fallbackOrder++;
        }
        fallbackOrder = addSystemColumn(result, policies, "received", "Received", true, fallbackOrder);
        fallbackOrder = addSystemColumn(result, policies, "stage", "Stage", true, fallbackOrder);
        fallbackOrder = addSystemColumn(result, policies, "documents", "Documents", true, fallbackOrder);
        fallbackOrder = addSystemColumn(result, policies, "attention", "Attention", true, fallbackOrder);
        fallbackOrder = addSystemColumn(result, policies, "intakeSource", "Intake Source", false, fallbackOrder);
        fallbackOrder = addSystemColumn(result, policies, "exportStatus", "Export Status", false, fallbackOrder);
        fallbackOrder = addSystemColumn(result, policies, "lastUpdated", "Last Updated", false, fallbackOrder);
        addSystemColumn(result, policies, "exportCycles", "Export Cycles", false, fallbackOrder);
        return result.stream().sorted(java.util.Comparator.comparingInt(DashboardColumnDefinition::displayOrder)
                .thenComparing(DashboardColumnDefinition::label)).toList();
    }

    private Map<String, ColumnPolicy> loadColumnPolicies(UUID tenant) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                select column_key,allowed_flag,default_visible_flag,display_order
                  from dashboard_column_settings
                 where tenant_id=:tenant
                """).setParameter("tenant", tenant).getResultList();
        Map<String, ColumnPolicy> result = new LinkedHashMap<>();
        for (Object[] row : rows) {
            result.put(string(row[0]), new ColumnPolicy(bool(row[1]), bool(row[2]), integer(row[3])));
        }
        return result;
    }

    private int addSystemColumn(List<DashboardColumnDefinition> columns, Map<String, ColumnPolicy> policies,
                                String key, String label, boolean defaultVisible, int fallbackOrder) {
        ColumnPolicy policy = policies.get(key);
        boolean allowed = policy == null || policy.allowed();
        if (allowed) {
            columns.add(new DashboardColumnDefinition(key, label, "SYSTEM", false, false,
                    policy == null ? defaultVisible : policy.defaultVisible(),
                    policy == null ? fallbackOrder : policy.displayOrder()));
        }
        return fallbackOrder + 1;
    }

    private record ColumnPolicy(boolean allowed, boolean defaultVisible, int displayOrder) {}

    private Map<UUID, List<DashboardIdentifierValue>> loadIdentifiers(UUID tenant, List<Object[]> packageRows,
                                                                        List<DashboardColumnDefinition> columns) {
        Set<UUID> ids = packageRows.stream().filter(r -> r[1] != null).map(r -> uuid(r[1]))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        if (ids.isEmpty()) return Map.of();
        StringBuilder sql = new StringBuilder("""
                select bri.business_record_id, bid.identifier_key, bid.label, bri.identifier_value,
                       bid.primary_flag, bid.display_order
                  from business_record_identifiers bri
                  join business_identifier_definitions bid on bid.id=bri.definition_id and bid.tenant_id=bri.tenant_id
                 where bri.tenant_id=?1 and bid.active_flag=true and bid.displayable_flag=true
                   and bri.business_record_id in (
                """);
        int parameter = 1;
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sql.append(',');
            sql.append('?').append(++parameter);
        }
        sql.append(") order by bid.display_order asc, bid.label asc");
        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter(1, tenant);
        parameter = 1;
        for (UUID id : ids) query.setParameter(++parameter, id);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        Set<String> allowedIdentifierKeys = columns.stream()
                .filter(column -> "IDENTIFIER".equals(column.kind()) && column.key().startsWith("identifier:"))
                .map(column -> column.key().substring("identifier:".length()))
                .collect(java.util.stream.Collectors.toSet());
        Map<UUID, List<DashboardIdentifierValue>> result = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String identifierKey = string(row[1]);
            if (!allowedIdentifierKeys.contains(identifierKey)) continue;
            UUID businessRecordId = uuid(row[0]);
            result.computeIfAbsent(businessRecordId, ignored -> new ArrayList<>()).add(
                    new DashboardIdentifierValue(identifierKey, string(row[2]), string(row[3]), bool(row[4]), integer(row[5])));
        }
        return result;
    }

    private String stageClause(DashboardStage stage) {
        return switch (stage == null ? DashboardStage.ALL : stage) {
            case ALL -> "";
            case PROCESSING -> " and p.status in ('CREATED','SUBMITTED','PROCESSING','APPROVED','VAULTED') ";
            case NEEDS_REVIEW -> " and p.status in ('PENDING_REVIEW','IN_REVIEW') ";
            case NEEDS_APPROVAL -> " and p.status='PENDING_FINAL_APPROVAL' ";
            case EXPORTED -> " and p.status='COMPLETED' ";
            case TECHNICAL_ATTENTION -> " and p.status='FAILED' ";
            case REJECTED -> " and p.status in ('REJECTED','CANCELLED') ";
        };
    }

    private String searchClause() {
        return """
                 and exists (
                   select 1
                     from business_record_identifiers bri
                     join business_identifier_definitions bid
                       on bid.id=bri.definition_id and bid.tenant_id=bri.tenant_id
                    where bri.tenant_id=p.tenant_id
                      and bri.business_record_id=p.business_record_id
                      and bid.active_flag=true and bid.searchable_flag=true
                      and lower(bri.identifier_value) like :search escape '!'
                 )
                """;
    }

    private void bindCommon(Query query, UUID tenant, String search) {
        query.setParameter("tenant", tenant);
        if (search != null && !search.isBlank()) query.setParameter("search", likeContains(search));
    }

    private static String likeContains(String value) {
        String normalized = value.toLowerCase(java.util.Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + normalized + "%";
    }

    private String orderExpression(String sortField) {
        return switch (sortField) {
            case "updatedAt" -> "p.updated_at";
            case "stage" -> "case p.status when 'FAILED' then 1 when 'PENDING_REVIEW' then 2 when 'IN_REVIEW' then 2 when 'PENDING_FINAL_APPROVAL' then 3 when 'PROCESSING' then 4 when 'SUBMITTED' then 4 when 'CREATED' then 4 when 'APPROVED' then 4 when 'VAULTED' then 4 when 'COMPLETED' then 5 else 6 end";
            default -> "p.created_at";
        };
    }

    private DashboardStage mapStage(String status) {
        return switch (status) {
            case "PENDING_REVIEW", "IN_REVIEW" -> DashboardStage.NEEDS_REVIEW;
            case "PENDING_FINAL_APPROVAL" -> DashboardStage.NEEDS_APPROVAL;
            case "COMPLETED" -> DashboardStage.EXPORTED;
            case "FAILED" -> DashboardStage.TECHNICAL_ATTENTION;
            case "REJECTED", "CANCELLED" -> DashboardStage.REJECTED;
            default -> DashboardStage.PROCESSING;
        };
    }

    private String attention(String status) {
        return switch (status) {
            case "PENDING_REVIEW", "IN_REVIEW" -> "REVIEW";
            case "PENDING_FINAL_APPROVAL" -> "APPROVAL";
            case "FAILED" -> "TECHNICAL";
            case "REJECTED", "CANCELLED" -> "REJECTED";
            default -> "NONE";
        };
    }

    private static Object[] row(Object value) {
        return value instanceof Object[] values ? values : new Object[]{value};
    }

    private static long number(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static int integer(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean b) return b;
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private static UUID uuid(Object value) {
        return value instanceof UUID id ? id : UUID.fromString(value.toString());
    }

    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant i) return i;
        if (value instanceof Timestamp t) return t.toInstant();
        if (value instanceof java.time.OffsetDateTime o) return o.toInstant();
        if (value instanceof java.time.LocalDateTime l) return l.toInstant(java.time.ZoneOffset.UTC);
        return Instant.parse(value.toString());
    }
}
