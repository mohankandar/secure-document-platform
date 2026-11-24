package com.securedoc.platform.application.port.out;

import com.securedoc.platform.application.audit.query.AuditRecordSummary;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Read-only facts for the compliance report (task 0017 B2). Every method is scoped to one tenant (the entity the
 * person is signed in to) and to the half-open period {@code [start, endExclusive)}. Nothing here returns a file
 * name, business identifier value or document text.
 */
public interface ComplianceReportReadPort {

    /** The latest SUCCESS record of {@code action} that happened before {@code before}. */
    Optional<AuditRecordSummary> latestSuccess(TenantId tenantId, String action, Instant before);

    /** Every SUCCESS record of {@code action} in the period, oldest first. */
    List<AuditRecordSummary> successes(TenantId tenantId, String action, Instant start, Instant endExclusive);

    /** How many records in the period match the query. */
    long count(TenantId tenantId, ActivityQuery query, Instant start, Instant endExclusive);

    /** Every record in the period, oldest first (time, then id), read in pages so any number fits. */
    void forEachRecord(TenantId tenantId, Instant start, Instant endExclusive, Consumer<AuditRecordSummary> consumer);

    /** Section 2 proof: see {@link RetentionCounts}. */
    RetentionCounts retention(TenantId tenantId, Instant start, Instant endExclusive);

    /** Section 3 counts that Reports does not show: see {@link CaseCounts}. */
    CaseCounts cases(TenantId tenantId, Instant start, Instant endExclusive);

    /** Section 5 (task 0064): how pages were read, and the AI models that took part. */
    default PageReading pageReading(TenantId tenantId, Instant start, Instant endExclusive) {
        return PageReading.NONE;
    }

    /**
     * From the {@code DOCUMENT_DETECTION_COMPLETED} successes that say how the pages were read.
     *
     * @param models the AI models (in their text form, {@code name|version|digest}) and the documents each took part in
     */
    record PageReading(long textPages, long signaturePages, long visionPages, long visionPagesToPerson,
                       List<ModelUse> models) {
        public static final PageReading NONE = new PageReading(0, 0, 0, 0, List.of());

        public PageReading {
            models = List.copyOf(models);
        }

        public boolean nothing() {
            return textPages + visionPages + visionPagesToPerson == 0 && models.isEmpty();
        }
    }

    record ModelUse(String model, long documents) { }

    /**
     * Records counted by one section 4 row: any of {@code actions} (empty = any action) with any of {@code outcomes}
     * (empty = any outcome); optionally not by the system actor; optionally only with this resource id.
     */
    record ActivityQuery(Set<String> actions, Set<String> outcomes, boolean excludeSystemActor, String resourceId) {
        public ActivityQuery {
            actions = actions == null ? Set.of() : Set.copyOf(actions);
            outcomes = outcomes == null ? Set.of() : Set.copyOf(outcomes);
        }
    }

    /**
     * From {@code package_lifecycles}, with the deletion time from the {@code ORIGINAL_ARTIFACT_PURGED} successes.
     *
     * @param due              cases whose original purge was due in the period: {@code original_delete_at} for a
     *                         delivered case, {@code technical_hold_until} for one never delivered
     * @param onTime           of those, the ones with an {@code ORIGINAL_ARTIFACT_PURGED} success no later than 24
     *                         hours after due
     * @param stillWithinKeep  cases delivered before the period end whose original was due after it and not yet
     *                         deleted at the end
     */
    record RetentionCounts(long due, long onTime, long stillWithinKeep) {
        public long lateOrFailed() {
            return Math.max(0, due - onTime);
        }
    }

    /**
     * From {@code reporting_package_facts} (the Reports table), for cases received in the period.
     *
     * @param rejected       received in the period, status {@code REJECTED}
     * @param stillOpenAtEnd received in the period, not first delivered before the period end, and not rejected or
     *                       cancelled
     * @param finishedByHand first delivered in the period (the Reports "Delivered" set) with a
     *                       {@code BUSINESS_MANUAL_FALLBACK_SUBMITTED} success
     */
    record CaseCounts(long rejected, long stillOpenAtEnd, long finishedByHand) { }
}
