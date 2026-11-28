package com.securedoc.platform.infrastructure.audit;

import com.securedoc.platform.application.audit.query.AuditRecordFilter;
import com.securedoc.platform.application.audit.query.AuditRecordSummary;
import com.securedoc.platform.application.port.out.AuditQueryPort;
import com.securedoc.platform.infrastructure.persistence.entity.AuditRecordEntity;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataAuditRecordRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Infrastructure adapter that queries audit records from the database.
 * All queries are tenant-scoped — tenantId is always part of the predicate.
 * UUID/Instant values are pre-validated by the API layer — no parsing here.
 */
@Component
public class JpaAuditQueryPortAdapter implements AuditQueryPort {

    private final SpringDataAuditRecordRepository auditRepo;

    public JpaAuditQueryPortAdapter(SpringDataAuditRecordRepository auditRepo) {
        this.auditRepo = auditRepo;
    }

    @Override
    public List<AuditRecordSummary> findByFilter(AuditRecordFilter filter) {
        Specification<AuditRecordEntity> spec = buildSpecification(filter);
        PageRequest page = PageRequest.of(0, filter.limit(),
                Sort.by(Sort.Direction.DESC, "occurredAt").and(Sort.by(Sort.Direction.DESC, "id")));

        return auditRepo.findAll(spec, page)
                .getContent()
                .stream()
                .map(this::toSummary)
                .toList();
    }

    /** Task 0119: never the system actor (automatic decisions). */
    private static final java.util.UUID SYSTEM_ACTOR = new java.util.UUID(0L, 0L);
    private static final int MOST_DECISIONS = 20_000;

    @Override
    public java.util.Set<String> findingsDecidedByAPerson(com.securedoc.platform.domain.identity.TenantId tenantId,
                                                         String caseId) {
        if (caseId == null || caseId.isBlank()) return java.util.Set.of();
        Specification<AuditRecordEntity> spec = (root, query, cb) -> cb.and(
                cb.equal(root.get("tenantId"), tenantId.value()),
                cb.equal(root.get("caseId"), caseId),
                cb.equal(root.get("resourceType"), "Finding"),
                root.get("action").in(PERSON_DECISIONS),
                cb.notEqual(root.get("actorId"), SYSTEM_ACTOR));
        java.util.Set<String> ids = new java.util.HashSet<>();
        auditRepo.findAll(spec, PageRequest.of(0, MOST_DECISIONS)).forEach(r -> ids.add(r.getResourceId()));
        return ids;
    }

    private Specification<AuditRecordEntity> buildSpecification(AuditRecordFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Always filter by tenant (mandatory)
            predicates.add(cb.equal(root.get("tenantId"), filter.tenantId().value()));

            filter.actionOpt().ifPresent(a -> predicates.add(cb.equal(root.get("action"), a)));
            if (!filter.actions().isEmpty()) predicates.add(root.get("action").in(filter.actions()));
            filter.outcomeOpt().ifPresent(o -> predicates.add(cb.equal(root.get("outcome"), o)));
            if (!filter.outcomes().isEmpty()) predicates.add(root.get("outcome").in(filter.outcomes()));
            filter.resourceTypeOpt().ifPresent(rt ->
                    predicates.add(cb.equal(root.get("resourceType"), rt)));
            filter.resourceIdOpt().ifPresent(ri ->
                    predicates.add(cb.equal(root.get("resourceId"), ri)));
            filter.actorIdOpt().ifPresent(ai ->
                    predicates.add(cb.equal(root.get("actorId"), ai)));
            filter.correlationIdOpt().ifPresent(ci ->
                    predicates.add(cb.equal(root.get("correlationId"), ci)));
            filter.fromOpt().ifPresent(from ->
                    predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), from)));
            filter.toOpt().ifPresent(to ->
                    predicates.add(cb.lessThanOrEqualTo(root.get("occurredAt"), to)));

            if (!filter.includeRoutineNoOpScans()) {
                // Exclude scheduler heartbeat/no-op scans in SQL before pagination. If this
                // were filtered in the UI after PageRequest, a page full of routine scans
                // could incorrectly render as an empty Audit Timeline.
                Predicate routineScheduledNoOpScan = cb.and(
                        cb.equal(root.get("action"), "WATCHED_FOLDER_SCAN_TRIGGERED"),
                        cb.isNotNull(root.get("detail")),
                        cb.like(cb.upper(root.get("detail")), "%MODE=SCHEDULED, DISCOVERED=0")
                );
                predicates.add(cb.not(routineScheduledNoOpScan));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private AuditRecordSummary toSummary(AuditRecordEntity entity) {
        return new AuditRecordSummary(
                entity.getId().toString(),
                entity.getActorId().toString(),
                entity.getAction(),
                entity.getResourceType(),
                entity.getResourceId(),
                entity.getOutcome(),
                entity.getCorrelationId().toString(),
                entity.getDetail(),
                entity.getOccurredAt(),
                entity.getActorDisplayName(),
                entity.getCaseId()
        );
    }
}
