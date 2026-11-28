package com.securedoc.platform.application.processing.service;

import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.domain.vision.VisionPageResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0116: an AI server outage (kind A) is told from a document's own failure by the servers' own check. */
class AiServerOutageTest {

    private final AtomicBoolean down = new AtomicBoolean();
    private final AtomicInteger asked = new AtomicInteger();
    private Instant now = Instant.parse("2026-11-28T21:40:00Z");
    private final AiServerOutage outage = new AiServerOutage(() -> {
        asked.incrementAndGet();
        return down.get();
    }, new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }, Duration.ofMinutes(1));

    @Test
    void onlyAnOutageKindOfFailureCanBeAnOutage() {
        for (TechnicalFailureCode code : new TechnicalFailureCode[]{TechnicalFailureCode.AI_UNAVAILABLE,
                TechnicalFailureCode.AI_TIMEOUT, TechnicalFailureCode.AI_RATE_LIMITED}) {
            assertTrue(AiServerOutage.outageKind(new RuntimeException(
                    new TechnicalDependencyException(TechnicalDependency.AI, code, true, "x"))), code.name());
        }
        assertFalse(AiServerOutage.outageKind(new TechnicalDependencyException(TechnicalDependency.AI,
                TechnicalFailureCode.AI_INVALID_RESPONSE, false, "an answer that can't be used is this page's")));
        assertFalse(AiServerOutage.outageKind(new TechnicalDependencyException(TechnicalDependency.OCR,
                TechnicalFailureCode.AI_TIMEOUT, true, "another dependency")));
        assertFalse(AiServerOutage.outageKind(new IllegalStateException("our defect")));
        assertTrue(AiServerOutage.outageKind(VisionPageResult.notRead(1, "LV-AI-001", "NOT_AVAILABLE")));
        assertFalse(AiServerOutage.outageKind(VisionPageResult.notRead(1, "LV-AI-005", "ANSWER_NOT_READABLE")));
        assertFalse(AiServerOutage.outageKind((VisionPageResult) null));
    }

    @Test
    void withTheServersWorkingTheFailureIsTheDocumentsAndNothingWaits() {
        assertFalse(outage.confirms());
        assertFalse(outage.paused());
    }

    @Test
    void theServersDownStartOneOutageWhichEndsWhenAProbeFindsThemBack() {
        down.set(true);
        assertTrue(outage.confirms());
        assertEquals(now, outage.pausedSince().orElseThrow());
        int checks = asked.get();
        assertTrue(outage.confirms(), "the outage goes on: not asked again for each failure");
        assertEquals(checks, asked.get());

        outage.probe();
        assertEquals(checks, asked.get(), "at most once per probe interval");
        now = now.plusSeconds(61);
        outage.probe();
        assertTrue(outage.paused(), "still down");
        down.set(false);
        now = now.plusSeconds(61);
        outage.probe();
        assertFalse(outage.paused(), "back: everything goes on by itself");
        assertFalse(outage.confirms(), "a later failure is a document's again");
    }

    @Test
    void neverWaitsWhereNoServerIsChecked() {
        AiServerOutage never = AiServerOutage.never();
        assertFalse(never.confirms());
        never.probe();
        assertFalse(never.paused());
    }
}
