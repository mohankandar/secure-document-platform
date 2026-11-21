package com.securedoc.platform.application.pipeline;

import com.securedoc.platform.application.port.out.PipelineJobRepository;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.DestinationState;
import com.securedoc.platform.domain.pipeline.ExportDestinationStatus;
import com.securedoc.platform.domain.pipeline.PipelineJob;
import com.securedoc.platform.domain.pipeline.PipelineJobStatus;
import com.securedoc.platform.domain.pipeline.PipelineStage;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The job queue in memory, following the same rules as {@code JdbcPipelineJobRepository} (whose SQL is proven against
 * PostgreSQL in {@code JdbcPipelineJobRepositoryPostgresTest}). One lock stands in for the row locks.
 */
final class InMemoryPipelineJobs implements PipelineJobRepository {
    private final Map<UUID, PipelineJob> rows = new LinkedHashMap<>();
    private final TestClock clock;
    private final InMemoryDestinationStates states;
    private long sequence;

    InMemoryPipelineJobs(TestClock clock, InMemoryDestinationStates states) {
        this.clock = clock;
        this.states = states;
    }

    synchronized List<PipelineJob> all() {
        return List.copyOf(rows.values());
    }

    synchronized PipelineJob get(UUID id) {
        return rows.get(id);
    }

    synchronized PipelineJob onlyJobOf(PackageId packageId, PipelineStage stage) {
        List<PipelineJob> found = rows.values().stream()
                .filter(j -> j.packageId().equals(packageId) && j.stage() == stage).toList();
        if (found.size() != 1) throw new AssertionError("expected one " + stage + " job, found " + found.size());
        return found.get(0);
    }

    @Override
    public synchronized boolean enqueue(PipelineJob job) {
        boolean exists = rows.values().stream().anyMatch(j -> j.tenantId().equals(job.tenantId())
                && j.stage() == job.stage() && j.packageId().equals(job.packageId())
                && j.exportCycle() == job.exportCycle() && Objects.equals(j.destinationId(), job.destinationId())
                && Objects.equals(j.itemId(), job.itemId()));
        if (exists) return false;
        // Like clock_timestamp(): every insert is a little later than the one before.
        Instant created = clock.instant().plusNanos(++sequence * 1000);
        rows.put(job.id(), new PipelineJob(job.id(), job.tenantId(), job.stage(), job.packageId(), job.documentId(),
                job.destinationId(), job.itemId(), job.exportCycle(), job.priority(), PipelineJobStatus.READY, 0,
                clock.instant(), null, null, 0L, null, null, created, created));
        return true;
    }

    @Override
    public synchronized Optional<PipelineJob> findByTenantIdAndId(TenantId tenantId, UUID id) {
        return Optional.ofNullable(rows.get(id)).filter(j -> j.tenantId().equals(tenantId));
    }

    @Override
    public synchronized List<PipelineJob> findByTenantIdAndPackageId(TenantId tenantId, PackageId packageId) {
        return rows.values().stream().filter(j -> j.tenantId().equals(tenantId) && j.packageId().equals(packageId))
                .sorted(Comparator.comparing(PipelineJob::createdAt)).toList();
    }

    @Override
    public synchronized List<PipelineJob> findByTenantIdAndStatus(TenantId tenantId, PipelineJobStatus status, int limit) {
        return rows.values().stream().filter(j -> j.tenantId().equals(tenantId) && j.status() == status)
                .sorted(Comparator.comparing(PipelineJob::updatedAt).thenComparing(PipelineJob::createdAt))
                .limit(limit).toList();
    }

    @Override
    public synchronized Optional<PipelineJob> claimNext(Set<PipelineStage> stages, String owner, Duration lease,
                                                        int perDestinationLimit) {
        Instant now = clock.instant();
        Optional<PipelineJob> next = rows.values().stream()
                .filter(j -> stages.contains(j.stage()))
                .filter(j -> j.status() == PipelineJobStatus.READY || j.status() == PipelineJobStatus.WAITING_RETRY)
                .filter(j -> !j.nextAttemptAt().isAfter(now))
                .filter(j -> j.destinationId() == null || states.find(j.tenantId(), j.destinationId())
                        .map(ExportDestinationStatus::state).orElse(DestinationState.WORKING) == DestinationState.WORKING)
                .filter(j -> j.destinationId() == null || rows.values().stream()
                        .filter(r -> j.destinationId().equals(r.destinationId())
                                && r.status() == PipelineJobStatus.RUNNING).count() < perDestinationLimit)
                .min(Comparator.comparingInt((PipelineJob j) -> j.priority().rank())
                        .thenComparing(PipelineJob::createdAt).thenComparing(PipelineJob::id));
        return next.map(j -> claimed(j, owner, lease));
    }

    @Override
    public synchronized Optional<PipelineJob> claim(TenantId tenantId, UUID id, Set<PipelineJobStatus> from, String owner,
                                                    Duration lease) {
        PipelineJob job = rows.get(id);
        if (job == null || !job.tenantId().equals(tenantId) || !from.contains(job.status())) return Optional.empty();
        return Optional.of(claimed(job, owner, lease));
    }

    private PipelineJob claimed(PipelineJob j, String owner, Duration lease) {
        Instant now = clock.instant();
        PipelineJob c = new PipelineJob(j.id(), j.tenantId(), j.stage(), j.packageId(), j.documentId(),
                j.destinationId(), j.itemId(), j.exportCycle(), j.priority(), PipelineJobStatus.RUNNING, j.attempts(),
                j.nextAttemptAt(), owner, now.plus(lease), j.claimToken() + 1, j.lastLvCode(), j.lastDetail(),
                j.createdAt(), now);
        rows.put(j.id(), c);
        return c;
    }

    @Override
    public synchronized boolean renew(UUID id, long claimToken, Duration lease) {
        if (!claimIsCurrent(id, claimToken)) return false;
        PipelineJob j = rows.get(id);
        rows.put(id, new PipelineJob(j.id(), j.tenantId(), j.stage(), j.packageId(), j.documentId(), j.destinationId(),
                j.itemId(), j.exportCycle(), j.priority(), j.status(), j.attempts(), j.nextAttemptAt(), j.claimOwner(),
                clock.instant().plus(lease), j.claimToken(), j.lastLvCode(), j.lastDetail(), j.createdAt(),
                clock.instant()));
        return true;
    }

    @Override
    public synchronized boolean claimIsCurrent(UUID id, long claimToken) {
        PipelineJob j = rows.get(id);
        return j != null && j.status() == PipelineJobStatus.RUNNING && j.claimToken() == claimToken
                && j.claimUntil() != null && j.claimUntil().isAfter(clock.instant());
    }

    @Override
    public synchronized boolean finish(UUID id, long claimToken, PipelineJobStatus status, int attempts,
                                       Duration retryDelay, String lastLvCode, String lastDetail) {
        if (!claimIsCurrent(id, claimToken)) return false;
        PipelineJob j = rows.get(id);
        Instant now = clock.instant();
        rows.put(id, new PipelineJob(j.id(), j.tenantId(), j.stage(), j.packageId(), j.documentId(), j.destinationId(),
                j.itemId(), j.exportCycle(), j.priority(), status, attempts,
                retryDelay == null ? now : now.plus(retryDelay), null, null, j.claimToken(), lastLvCode, lastDetail,
                j.createdAt(), now));
        return true;
    }

    @Override
    public synchronized List<PipelineJob> releaseExpiredClaims(int maxAttempts, String exhaustedLvCode, int limit) {
        Instant now = clock.instant();
        List<PipelineJob> released = new ArrayList<>();
        for (PipelineJob j : List.copyOf(rows.values())) {
            if (j.status() != PipelineJobStatus.RUNNING || j.claimUntil().isAfter(now)) continue;
            int attempts = j.attempts() + 1;
            boolean exhausted = attempts >= maxAttempts;
            PipelineJob r = new PipelineJob(j.id(), j.tenantId(), j.stage(), j.packageId(), j.documentId(),
                    j.destinationId(), j.itemId(), j.exportCycle(), j.priority(),
                    exhausted ? PipelineJobStatus.NEEDS_IT : PipelineJobStatus.READY, attempts, now, null, null,
                    j.claimToken(), exhausted ? exhaustedLvCode : j.lastLvCode(), "CLAIM_EXPIRED", j.createdAt(), now);
            rows.put(j.id(), r);
            released.add(r);
            if (released.size() >= limit) break;
        }
        return released;
    }

    @Override
    public synchronized boolean changeStatus(TenantId tenantId, UUID id, PipelineJobStatus from, PipelineJobStatus to,
                                             int attempts, String lastLvCode, String lastDetail) {
        PipelineJob j = rows.get(id);
        if (j == null || !j.tenantId().equals(tenantId) || j.status() != from) return false;
        Instant now = clock.instant();
        rows.put(id, new PipelineJob(j.id(), j.tenantId(), j.stage(), j.packageId(), j.documentId(), j.destinationId(),
                j.itemId(), j.exportCycle(), j.priority(), to, attempts, now, null, null, j.claimToken(), lastLvCode,
                lastDetail, j.createdAt(), now));
        return true;
    }

    @Override
    public synchronized int makeDueNow(TenantId tenantId, IntegrationConnectionId destinationId) {
        int changed = 0;
        Instant now = clock.instant();
        for (PipelineJob j : List.copyOf(rows.values())) {
            if (!j.tenantId().equals(tenantId) || !destinationId.equals(j.destinationId())
                    || j.status() != PipelineJobStatus.WAITING_RETRY) continue;
            rows.put(j.id(), new PipelineJob(j.id(), j.tenantId(), j.stage(), j.packageId(), j.documentId(),
                    j.destinationId(), j.itemId(), j.exportCycle(), j.priority(), PipelineJobStatus.READY, j.attempts(),
                    now, null, null, j.claimToken(), j.lastLvCode(), j.lastDetail(), j.createdAt(), now));
            changed++;
        }
        return changed;
    }
}
