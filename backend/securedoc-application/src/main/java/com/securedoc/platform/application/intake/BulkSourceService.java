package com.securedoc.platform.application.intake;

import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.BulkSourceRepository;
import com.securedoc.platform.application.port.out.BulkSourceRepository.BulkSource;
import com.securedoc.platform.application.port.out.EntityTimeZonePort;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.BulkWindow;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Task 0079: sources marked Bulk (migrations): their window, whether it is open, and how far they are. */
public final class BulkSourceService {
    /** The pace is that of the last seven days. */
    private static final Duration PACE = Duration.ofDays(7);

    private final BulkSourceRepository repository;
    private final EntityTimeZonePort zones;
    private final AuditService auditService;
    private final Clock clock;

    public BulkSourceService(BulkSourceRepository repository, EntityTimeZonePort zones, AuditService auditService,
                             Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.zones = Objects.requireNonNull(zones);
        this.auditService = Objects.requireNonNull(auditService);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * @param open           whether its cases are worked on now
     * @param perOpenHour    cases exported per hour the window was open; null before any was
     * @param expectedFinish at that pace, counting only the window's hours; null when not known
     */
    public record Progress(String sourceKey, BulkWindow window, boolean open, long done, long waiting, long failed,
                           Double perOpenHour, Instant expectedFinish) { }

    /** Every source whose window is open now, as the queue is asked with it. Without a time zone a window never opens. */
    public Set<String> openSources() {
        List<BulkSource> all = repository.all();
        // Unmarked sources' last jobs must not wait for a window that is gone.
        if (all.isEmpty()) return Set.of(com.securedoc.platform.application.port.out.PipelineJobRepository.EVERY_BULK_SOURCE);
        return all.stream().filter(this::open).map(BulkSource::reference).collect(Collectors.toSet());
    }

    public boolean bulk(TenantId tenantId, String sourceKey) {
        return sourceKey != null && repository.find(tenantId, sourceKey).isPresent();
    }

    /** False only for a Bulk source whose window is closed. */
    public boolean mayTakeIn(TenantId tenantId, String sourceKey) {
        return repository.find(tenantId, sourceKey).map(this::open).orElse(true);
    }

    /** Cases already taken in keep their priority. */
    public void mark(TenantId tenantId, ActorId actorId, String sourceKey, BulkWindow window, CorrelationId correlationId) {
        if (zones.effective(tenantId).isEmpty()) {
            throw new ValidationException(LvErrorCode.CFG_001, "Set the entity's time zone first: the window is in its time");
        }
        repository.save(tenantId, sourceKey, window);
        audit(tenantId, actorId, sourceKey, correlationId, "bulk=true, window=" + window.from() + "-" + window.to()
                + ", weekends=" + window.weekends());
    }

    public void unmark(TenantId tenantId, ActorId actorId, String sourceKey, CorrelationId correlationId) {
        if (repository.find(tenantId, sourceKey).isEmpty()) return;
        repository.remove(tenantId, sourceKey);
        audit(tenantId, actorId, sourceKey, correlationId, "bulk=false");
    }

    public List<Progress> progress(TenantId tenantId) {
        return repository.all().stream().filter(s -> s.tenantId().equals(tenantId)).map(this::progress).toList();
    }

    private Progress progress(BulkSource source) {
        var counts = repository.counts(source.tenantId(), source.sourceKey());
        Optional<ZoneId> zone = zones.effective(source.tenantId());
        Double perHour = null;
        Instant finish = null;
        if (zone.isPresent()) {
            ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(zone.get());
            Instant since = now.toInstant().minus(PACE);
            if (source.markedAt().isAfter(since)) since = source.markedAt();
            Duration worked = source.window().openTime(since.atZone(zone.get()), now);
            long done = repository.doneSince(source.tenantId(), source.sourceKey(), since);
            if (done > 0 && !worked.isZero()) {
                perHour = done * 3600.0 / worked.toSeconds();
                finish = counts.waiting() == 0 ? null : source.window().after(now,
                        Duration.ofSeconds((long) (counts.waiting() / perHour * 3600))).map(ZonedDateTime::toInstant)
                        .orElse(null);
            }
        }
        return new Progress(source.sourceKey(), source.window(), open(source), counts.done(), counts.waiting(),
                counts.failed(), perHour, finish);
    }

    private boolean open(BulkSource source) {
        return zones.effective(source.tenantId())
                .map(zone -> source.window().open(ZonedDateTime.now(clock).withZoneSameInstant(zone))).orElse(false);
    }

    private void audit(TenantId tenantId, ActorId actorId, String sourceKey, CorrelationId correlationId, String detail) {
        auditService.recordSuccess(tenantId, actorId, AuditAction.SOURCE_BULK_CHANGED, "IntakeSource", sourceKey,
                correlationId, detail);
    }
}
