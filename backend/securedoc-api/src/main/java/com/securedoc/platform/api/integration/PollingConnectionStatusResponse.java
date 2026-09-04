package com.securedoc.platform.api.integration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PollingConnectionStatusResponse(UUID connectionId, String name, boolean enabled, String healthStatus,
                                              Instant nextScheduledAt, List<PollingRunResponse> recentRuns) {}
