package com.securedoc.platform.application.lifecycle.service;

import com.securedoc.platform.application.port.out.UnrecordedFileSweepPort;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Task 0049, step 5: a server that dies after a step stored its file and before its record was saved leaves a file (a
 * text read or a redacted copy) with no record. This sweep, part of the lifecycle maintenance, removes such files after
 * a safe delay, in both pipelines. At most once an hour per server, because it lists the storage folders. An entity
 * with files removed gets one audit record per run, with counts only.
 */
public class UnrecordedFileSweepService {
    static final Duration BETWEEN_RUNS = Duration.ofHours(1);
    static final int MAX_PER_RUN = 1000;

    private final UnrecordedFileSweepPort files;
    private final AuditService audit;
    private final Duration delay;
    private Instant lastRun;

    /** @param keepHours how long a file without a record is kept (1 to 8760) */
    public UnrecordedFileSweepService(UnrecordedFileSweepPort files, AuditService audit, int keepHours) {
        this.files = Objects.requireNonNull(files);
        this.audit = Objects.requireNonNull(audit);
        if (keepHours < 1 || keepHours > 8760) {
            throw new IllegalArgumentException("securedoc.lifecycle.unrecorded-files-keep-hours must be 1 to 8760");
        }
        this.delay = Duration.ofHours(keepHours);
    }

    /** The number of files removed. */
    public synchronized int run(Instant now, ActorId actorId, CorrelationId correlationId) {
        if (lastRun != null && now.isBefore(lastRun.plus(BETWEEN_RUNS))) return 0;
        lastRun = now;
        int total = 0;
        for (var entry : files.removeUnrecorded(now.minus(delay), MAX_PER_RUN).entrySet()) {
            var removed = entry.getValue();
            if (removed.total() == 0) continue;
            total += removed.total();
            audit.recordSuccess(entry.getKey(), actorId, AuditAction.UNRECORDED_FILES_CLEANED, "Tenant",
                    entry.getKey().toString(), correlationId, "Files without a record removed: text="
                            + removed.textFiles() + ", redacted=" + removed.redactedFiles());
        }
        return total;
    }
}
