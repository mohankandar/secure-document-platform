package com.securedoc.platform.scanbridge.service;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

final class StableFileTracker {
    record Observation(long size, long lastModifiedMillis, Instant unchangedSince) { }

    private final Map<Path, Observation> observations = new HashMap<>();

    boolean isStable(Path path, long size, long lastModifiedMillis, Instant now, Duration stableWindow) {
        Path key = path.toAbsolutePath().normalize();
        Observation previous = observations.get(key);
        if (previous == null || previous.size() != size || previous.lastModifiedMillis() != lastModifiedMillis) {
            observations.put(key, new Observation(size, lastModifiedMillis, now));
            return false;
        }
        return !now.isBefore(previous.unchangedSince().plus(stableWindow));
    }

    void forget(Path path) {
        observations.remove(path.toAbsolutePath().normalize());
    }
}
