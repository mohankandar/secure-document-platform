package com.securedoc.platform.application.processing.service;

import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.vision.VisionPageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Task 0116, phase 1 (owner 2026-11-27): an AI server outage makes the work wait; it never stops a case. Kind A of the
 * owner's three kinds of failure.
 *
 * <p>When an AI step fails as an outage does (can't connect, timeout, busy), the AI servers' own check is asked. Down: the
 * outage starts, the step's work is not kept, its job waits without using a try, and no FIND, VISION or REDACT job is
 * taken for any case until a probe finds the servers back. Then everything goes on by itself. Up: it is this document
 * (kind B or C), handled as before. The alert is the existing "AI model down" condition (one per outage, and one
 * Recovered). Codes and times only.
 *
 * <p>Held in memory by each server, as its workers are (a later phase may share it through the database).
 */
public final class AiServerOutage {
    private static final Logger log = LoggerFactory.getLogger(AiServerOutage.class);

    /** The job's detail while it waits for the AI servers. */
    public static final String WAITING_FOR_AI_SERVER = "WAITING_FOR_AI_SERVER";
    private static final Set<TechnicalFailureCode> OUTAGE_CODES = EnumSet.of(TechnicalFailureCode.AI_UNAVAILABLE,
            TechnicalFailureCode.AI_TIMEOUT, TechnicalFailureCode.AI_RATE_LIMITED);
    private static final Set<String> OUTAGE_LV_CODES = Set.of(LvErrorCode.AI_001.code(), LvErrorCode.AI_002.code(),
            LvErrorCode.AI_003.code());

    /** No outage is ever found: nothing waits (tests and set-ups without a model server). */
    public static AiServerOutage never() {
        return new AiServerOutage(() -> false, Clock.systemUTC(), Duration.ofMinutes(1));
    }

    private final BooleanSupplier serversDown;
    private final Clock clock;
    private final Duration probeEvery;
    private volatile Instant since;
    private Instant lastProbe;

    /**
     * @param serversDown the AI servers' own check: true when none of them answers (a slow check is fine: it is asked
     *                    only after a failure and at most once per {@code probeEvery} during an outage)
     */
    public AiServerOutage(BooleanSupplier serversDown, Clock clock, Duration probeEvery) {
        this.serversDown = Objects.requireNonNull(serversDown);
        this.clock = Objects.requireNonNull(clock);
        this.probeEvery = Objects.requireNonNull(probeEvery);
        if (probeEvery.isNegative() || probeEvery.isZero()) throw new IllegalArgumentException("probeEvery");
    }

    /** Since when the AI work waits; empty while the servers work. */
    public Optional<Instant> pausedSince() {
        return Optional.ofNullable(since);
    }

    public boolean paused() {
        return since != null;
    }

    /** How long a waiting job waits before it is due again (the probe brings the stage back sooner). */
    public Duration probeEvery() {
        return probeEvery;
    }

    /** Whether this failure is of the kind an outage gives (it may still be this document's: see {@link #confirms}). */
    public static boolean outageKind(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof TechnicalDependencyException technical) {
                return technical.dependency() == TechnicalDependency.AI && OUTAGE_CODES.contains(technical.failureCode());
            }
        }
        return false;
    }

    /** Whether a vision page that was not read failed as an outage does. */
    public static boolean outageKind(VisionPageResult page) {
        return page != null && !page.read() && page.code() != null && OUTAGE_LV_CODES.contains(page.code());
    }

    /**
     * An AI step failed as an outage does: true when the AI servers are down (the outage starts now, or goes on) and the
     * work must wait; false when they work (the failure is this document's).
     */
    public synchronized boolean confirms() {
        if (since != null) return true;
        if (!serversDown.getAsBoolean()) return false;
        since = clock.instant();
        lastProbe = since;
        log.warn("AI_SERVER_OUTAGE_STARTED reasonCode=AI_SERVER_UNREACHABLE lvCode={}", LvErrorCode.AI_001.code());
        return true;
    }

    /** During an outage, at most once per {@code probeEvery}: when the servers work again, the outage ends. */
    public synchronized void probe() {
        if (since == null) return;
        Instant now = clock.instant();
        if (lastProbe != null && now.isBefore(lastProbe.plus(probeEvery))) return;
        lastProbe = now;
        if (serversDown.getAsBoolean()) return;
        log.info("AI_SERVER_OUTAGE_ENDED durationSeconds={}", Duration.between(since, now).toSeconds());
        since = null;
    }
}
