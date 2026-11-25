package com.securedoc.platform.api.intake;

import com.securedoc.platform.application.intake.IntakeGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Task 0067: the safety stop and the pacing of intake. Below {@code stopBelow} percent free on any document storage disk,
 * or of the database size IT set, nothing is taken in until every one is above {@code resumeAbove} percent again. What
 * cannot be measured changes nothing: a stop stays a stop. Measured at most every few seconds.
 */
public final class IntakeSpaceGate implements IntakeGate {
    private static final Logger log = LoggerFactory.getLogger(IntakeSpaceGate.class);
    private static final int MOST_SLOWDOWN = 8;
    private static final long MEASURED_EVERY_NANOS = 5_000_000_000L;

    /** Percent of a disk that is free; empty when it cannot be told. */
    interface FreeDisk {
        Double percent(Path folder) throws java.io.IOException;
    }

    public record Settings(int stopBelow, int resumeAbove, int retryAfterSeconds, long databaseBytes, int longQueue) {
        public Settings {
            if (stopBelow < 1 || resumeAbove <= stopBelow || resumeAbove > 99) {
                throw new IllegalStateException(
                        "securedoc.intake.space: stop-below-percent must be 1 or more and below resume-above-percent (up to 99)");
            }
            if (retryAfterSeconds < 1 || databaseBytes < 0 || longQueue < 1) {
                throw new IllegalStateException("securedoc.intake: retry-after-seconds and long-queue must be 1 or more, "
                        + "database-size-gb 0 or more");
            }
        }
    }

    private final Settings settings;
    private final Supplier<List<String>> folders;
    private final LongSupplier databaseBytes;
    private final IntSupplier queueLength;
    private final FreeDisk freeDisk;
    private final LongSupplier clock;
    private com.securedoc.platform.application.intake.BulkSourceService bulk;
    private Long measuredAt;
    private boolean full;

    public IntakeSpaceGate(Settings settings, Supplier<List<String>> folders, LongSupplier databaseBytes,
                           IntSupplier queueLength) {
        this(settings, folders, databaseBytes, queueLength, IntakeSpaceGate::freePercent, System::nanoTime);
    }

    IntakeSpaceGate(Settings settings, Supplier<List<String>> folders, LongSupplier databaseBytes,
                    IntSupplier queueLength, FreeDisk freeDisk, LongSupplier clock) {
        this.settings = settings;
        this.folders = folders;
        this.databaseBytes = databaseBytes;
        this.queueLength = queueLength;
        this.freeDisk = freeDisk;
        this.clock = clock;
    }

    @Override
    public int slowdown() {
        int factor = 1;
        for (long most = settings.longQueue(); queueLength.getAsInt() >= most && factor < MOST_SLOWDOWN; most *= 2) factor *= 2;
        return factor;
    }

    @Override
    public synchronized boolean full() {
        long now = clock.getAsLong();
        if (measuredAt != null && now - measuredAt < MEASURED_EVERY_NANOS) return full;
        measuredAt = now;
        boolean before = full;
        List<Double> free = new ArrayList<>();
        boolean allKnown = measure(free);
        if (free.stream().anyMatch(percent -> percent < settings.stopBelow())) full = true;
        else if (full && allKnown && free.stream().allMatch(percent -> percent > settings.resumeAbove())) full = false;
        if (full != before) {
            log.warn(full ? "INTAKE_SAFETY_STOP code=LV-INT-027 freePercent={}" : "INTAKE_SAFETY_STOP_ENDED freePercent={}",
                    free.stream().mapToDouble(Double::doubleValue).min().orElse(0));
        }
        return full;
    }

    /** Task 0079. */
    IntakeSpaceGate withBulk(com.securedoc.platform.application.intake.BulkSourceService bulk) {
        this.bulk = bulk;
        return this;
    }

    @Override
    public boolean sourceOpen(com.securedoc.platform.domain.identity.TenantId tenantId, String sourceKey) {
        return bulk == null || bulk.mayTakeIn(tenantId, sourceKey);
    }

    @Override
    public int retryAfterSeconds() {
        return settings.retryAfterSeconds();
    }

    private boolean measure(List<Double> free) {
        boolean known = true;
        try {
            for (String folder : folders.get()) {
                try {
                    Double percent = freeDisk.percent(Path.of(folder));
                    if (percent == null) known = false;
                    else free.add(percent);
                } catch (java.io.IOException | RuntimeException notKnown) {
                    known = false;
                }
            }
            if (settings.databaseBytes() > 0) {
                free.add(100.0 * (settings.databaseBytes() - databaseBytes.getAsLong()) / settings.databaseBytes());
            }
        } catch (RuntimeException notKnown) {
            known = false;
        }
        return known;
    }

    private static Double freePercent(Path folder) throws java.io.IOException {
        var store = Files.getFileStore(folder);
        return store.getTotalSpace() <= 0 ? null : 100.0 * store.getUsableSpace() / store.getTotalSpace();
    }
}
