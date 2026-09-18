package com.securedoc.platform.scanbridge.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StableFileTrackerTest {
    @Test
    void requiresFileToRemainUnchangedForConfiguredWindow() {
        StableFileTracker tracker = new StableFileTracker();
        Path path = Path.of("scan.pdf");
        Instant t0 = Instant.parse("2026-09-09T20:00:00Z");

        assertFalse(tracker.isStable(path, 100, 10, t0, Duration.ofSeconds(2)));
        assertFalse(tracker.isStable(path, 100, 10, t0.plusSeconds(1), Duration.ofSeconds(2)));
        assertTrue(tracker.isStable(path, 100, 10, t0.plusSeconds(2), Duration.ofSeconds(2)));
    }

    @Test
    void fileChangeRestartsStabilityWindow() {
        StableFileTracker tracker = new StableFileTracker();
        Path path = Path.of("scan.pdf");
        Instant t0 = Instant.parse("2026-09-09T20:00:00Z");

        assertFalse(tracker.isStable(path, 100, 10, t0, Duration.ofSeconds(2)));
        assertFalse(tracker.isStable(path, 200, 11, t0.plusSeconds(2), Duration.ofSeconds(2)));
        assertFalse(tracker.isStable(path, 200, 11, t0.plusSeconds(3), Duration.ofSeconds(2)));
        assertTrue(tracker.isStable(path, 200, 11, t0.plusSeconds(4), Duration.ofSeconds(2)));
    }
}
