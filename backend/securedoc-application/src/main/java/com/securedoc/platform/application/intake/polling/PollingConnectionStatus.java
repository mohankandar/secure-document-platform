package com.securedoc.platform.application.intake.polling;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PollingConnectionStatus(UUID connectionId, String name, boolean enabled, String healthStatus,
                                      Instant nextScheduledAt, List<PollingRunSnapshot> recentRuns) {}
