package com.securedoc.platform.application.compliance;

import com.securedoc.platform.application.audit.query.AuditRecordSummary;
import com.securedoc.platform.application.operations.model.OperationalReportSnapshot;
import com.securedoc.platform.application.port.out.ComplianceReportReadPort;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** In-memory stand-ins for the compliance report's reads (task 0017 tests). */
final class ComplianceFakes {

    private ComplianceFakes() {
    }

    static AuditRecordSummary record(String action, String outcome, String actorId, String displayName,
                                     String resourceId, String detail, Instant at, String caseId) {
        return new AuditRecordSummary(UUID.randomUUID().toString(), actorId, action, "Test", resourceId, outcome,
                UUID.randomUUID().toString(), detail, at, displayName, caseId);
    }

    /** Reads from a list of records, as the database adapter does from {@code audit_records}. */
    static final class ReadPort implements ComplianceReportReadPort {
        final List<AuditRecordSummary> records = new ArrayList<>();
        RetentionCounts retention = new RetentionCounts(0, 0, 0);
        CaseCounts cases = new CaseCounts(0, 0, 0);

        private List<AuditRecordSummary> sorted() {
            return records.stream().sorted(Comparator.comparing(AuditRecordSummary::occurredAt)
                    .thenComparing(AuditRecordSummary::id)).toList();
        }

        @Override
        public Optional<AuditRecordSummary> latestSuccess(com.securedoc.platform.domain.identity.TenantId t,
                                                          String action, Instant before) {
            return sorted().stream().filter(r -> r.action().equals(action) && r.outcome().equals("SUCCESS")
                    && r.occurredAt().isBefore(before)).reduce((a, b) -> b);
        }

        @Override
        public List<AuditRecordSummary> successes(com.securedoc.platform.domain.identity.TenantId t, String action,
                                                  Instant start, Instant end) {
            return sorted().stream().filter(r -> r.action().equals(action) && r.outcome().equals("SUCCESS")
                    && !r.occurredAt().isBefore(start) && r.occurredAt().isBefore(end)).toList();
        }

        @Override
        public long count(com.securedoc.platform.domain.identity.TenantId t, ActivityQuery q, Instant start, Instant end) {
            return records.stream().filter(r -> !r.occurredAt().isBefore(start) && r.occurredAt().isBefore(end))
                    .filter(r -> q.actions().isEmpty() || q.actions().contains(r.action()))
                    .filter(r -> q.outcomes().isEmpty() || q.outcomes().contains(r.outcome()))
                    .filter(r -> !q.excludeSystemActor() || !AuditSentences.SYSTEM_ACTOR_ID.equals(r.actorId()))
                    .filter(r -> q.resourceId() == null || q.resourceId().equals(r.resourceId()))
                    .count();
        }

        @Override
        public void forEachRecord(com.securedoc.platform.domain.identity.TenantId t, Instant start, Instant end,
                                  Consumer<AuditRecordSummary> consumer) {
            sorted().stream().filter(r -> !r.occurredAt().isBefore(start) && r.occurredAt().isBefore(end))
                    .forEach(consumer);
        }

        @Override
        public RetentionCounts retention(com.securedoc.platform.domain.identity.TenantId t, Instant s, Instant e) {
            return retention;
        }

        @Override
        public CaseCounts cases(com.securedoc.platform.domain.identity.TenantId t, Instant s, Instant e) {
            return cases;
        }
    }

    /** A Reports snapshot holding only the numbers the compliance report reads. */
    static OperationalReportSnapshot reports(Instant from, Instant to, long received, long delivered, long reviewed,
                                             long straightThrough) {
        return new OperationalReportSnapshot(Instant.now(),
                new OperationalReportSnapshot.Period(from, to, from, from, "ALL", "ALL"),
                new OperationalReportSnapshot.Executive(received, 0, 0, 0, 0, 0, delivered, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                        0, 0, 0, 0, List.of()),
                new OperationalReportSnapshot.Intake(received, 0, 0, 0, 0, 0, 0, 0),
                new OperationalReportSnapshot.Processing(0, 0, 0, 0, 0, 0),
                new OperationalReportSnapshot.Export(0, 0, 0),
                new OperationalReportSnapshot.Retention(0, 0, 0, 0, 0, 0),
                new OperationalReportSnapshot.Timing(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                new OperationalReportSnapshot.Backlog(0, 0, 0, 0, 0, 0),
                new OperationalReportSnapshot.Usage(received, 0, 0, 0, 0, reviewed, straightThrough, 0),
                new OperationalReportSnapshot.ServiceLevel(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                List.of());
    }
}
