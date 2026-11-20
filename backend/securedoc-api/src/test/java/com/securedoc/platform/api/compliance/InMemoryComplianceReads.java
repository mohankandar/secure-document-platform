package com.securedoc.platform.api.compliance;

import com.securedoc.platform.application.audit.query.AuditRecordSummary;
import com.securedoc.platform.application.audit.RequestActorDisplayName;
import com.securedoc.platform.application.compliance.ComplianceReportService;
import com.securedoc.platform.application.operations.model.OperationalReportSnapshot;
import com.securedoc.platform.application.port.in.GetOperationalReportsQuery;
import com.securedoc.platform.application.port.out.ComplianceReportReadPort;
import com.securedoc.platform.application.port.out.TenantLifecyclePolicyRepository;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.application.port.out.TenantRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.lifecycle.TenantLifecyclePolicy;
import com.securedoc.platform.domain.tenant.Tenant;
import com.securedoc.platform.infrastructure.compliance.PdfBoxComplianceReportAdapter;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The compliance report's reads over an in-memory list of audit records (task 0017 API tests), wired to the real
 * service and the real PDF adapter. Only records of {@code tenant} are ever returned.
 */
public final class InMemoryComplianceReads implements ComplianceReportReadPort {

    private final List<AuditRecord> records;
    private final TenantId tenant;

    public InMemoryComplianceReads(List<AuditRecord> records, TenantId tenant) {
        this.records = records;
        this.tenant = tenant;
    }

    /** The real service and PDF adapter; Reports numbers are zero; audits go to {@code audits}. */
    public static ComplianceReportService service(List<AuditRecord> source, List<AuditRecord> audits,
                                                  List<Tenant> tenants, TenantId readsFor,
                                                  RequestActorDisplayName names, Clock clock) {
        GetOperationalReportsQuery reports = (p, from, to, s, o) -> emptyReports(from, to);
        TenantRepository tenantRepository = new TenantRepository() {
            public Optional<Tenant> findById(TenantId id) {
                return tenants.stream().filter(t -> t.id().equals(id)).findFirst();
            }
            public Optional<Tenant> findBySlug(String slug) { return Optional.empty(); }
            public List<Tenant> findAll() { return tenants; }
            public void save(Tenant t) { }
        };
        TenantRedactionPolicyRepository policies = new TenantRedactionPolicyRepository() {
            public Optional<TenantRedactionPolicy> findByTenantId(TenantId id) { return Optional.empty(); }
            public void save(TenantRedactionPolicy p) { }
        };
        TenantLifecyclePolicyRepository lifecycle = new TenantLifecyclePolicyRepository() {
            public Optional<TenantLifecyclePolicy> findByTenantId(TenantId id) { return Optional.empty(); }
            public void save(TenantLifecyclePolicy p) { }
        };
        return new ComplianceReportService(new InMemoryComplianceReads(source, readsFor),
                new PdfBoxComplianceReportAdapter(), reports, tenantRepository, policies, lifecycle,
                new AuditService(audits::add, names), names, clock);
    }

    private List<AuditRecordSummary> mine(Instant start, Instant end) {
        return records.stream().filter(r -> r.tenantId().equals(tenant))
                .filter(r -> start == null || !r.timestamp().isBefore(start))
                .filter(r -> end == null || r.timestamp().isBefore(end))
                .sorted(Comparator.comparing(AuditRecord::timestamp).thenComparing(r -> r.id().toString()))
                .map(InMemoryComplianceReads::summary).toList();
    }

    static AuditRecordSummary summary(AuditRecord r) {
        return new AuditRecordSummary(r.id().toString(), r.actorId().toString(), r.action().name(), r.resourceType(),
                r.resourceId(), r.outcome().name(), r.correlationId().toString(), r.detail(), r.timestamp(),
                r.actorDisplayName(), r.caseId());
    }

    @Override
    public Optional<AuditRecordSummary> latestSuccess(TenantId t, String action, Instant before) {
        return mine(null, before).stream().filter(r -> r.action().equals(action) && r.outcome().equals("SUCCESS"))
                .reduce((a, b) -> b);
    }

    @Override
    public List<AuditRecordSummary> successes(TenantId t, String action, Instant start, Instant end) {
        return mine(start, end).stream().filter(r -> r.action().equals(action) && r.outcome().equals("SUCCESS")).toList();
    }

    @Override
    public long count(TenantId t, ActivityQuery q, Instant start, Instant end) {
        return mine(start, end).stream()
                .filter(r -> q.actions().isEmpty() || q.actions().contains(r.action()))
                .filter(r -> q.outcomes().isEmpty() || q.outcomes().contains(r.outcome()))
                .filter(r -> !q.excludeSystemActor() || !RequestActorDisplayName.SYSTEM_ACTOR_ID.toString().equals(r.actorId()))
                .filter(r -> q.resourceId() == null || q.resourceId().equals(r.resourceId()))
                .count();
    }

    @Override
    public void forEachRecord(TenantId t, Instant start, Instant end, Consumer<AuditRecordSummary> consumer) {
        mine(start, end).forEach(consumer);
    }

    @Override
    public RetentionCounts retention(TenantId t, Instant start, Instant end) {
        return new RetentionCounts(0, 0, 0);
    }

    @Override
    public CaseCounts cases(TenantId t, Instant start, Instant end) {
        return new CaseCounts(0, 0, 0);
    }

    static OperationalReportSnapshot emptyReports(Instant from, Instant to) {
        return new OperationalReportSnapshot(Instant.now(),
                new OperationalReportSnapshot.Period(from, to, from, from, "ALL", "ALL"),
                new OperationalReportSnapshot.Executive(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of()),
                new OperationalReportSnapshot.Intake(0, 0, 0, 0, 0, 0, 0, 0),
                new OperationalReportSnapshot.Processing(0, 0, 0, 0, 0, 0),
                new OperationalReportSnapshot.Export(0, 0, 0),
                new OperationalReportSnapshot.Retention(0, 0, 0, 0, 0, 0),
                new OperationalReportSnapshot.Timing(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                new OperationalReportSnapshot.Backlog(0, 0, 0, 0, 0, 0),
                new OperationalReportSnapshot.Usage(0, 0, 0, 0, 0, 0, 0, 0),
                new OperationalReportSnapshot.ServiceLevel(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                List.of());
    }
}
