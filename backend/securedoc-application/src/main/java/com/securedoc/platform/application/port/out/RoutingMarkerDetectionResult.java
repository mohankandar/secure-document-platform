package com.securedoc.platform.application.port.out;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Immutable deterministic routing-marker scan result. */
public record RoutingMarkerDetectionResult(
        Map<UUID, List<Integer>> pagesByRule,
        Set<Integer> ambiguousPages) {

    public RoutingMarkerDetectionResult {
        pagesByRule = Map.copyOf(pagesByRule == null ? Map.of() : pagesByRule);
        ambiguousPages = Set.copyOf(ambiguousPages == null ? Set.of() : ambiguousPages);
    }
}
