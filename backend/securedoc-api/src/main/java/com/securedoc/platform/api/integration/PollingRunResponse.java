package com.securedoc.platform.api.integration;

import java.time.Instant;
import java.util.UUID;

public record PollingRunResponse(UUID runId, UUID connectionId, String trigger, String status, Instant startedAt,
                                 Instant completedAt, int packagesDiscovered, int documentsExpected,
                                 int documentsRetrieved, int packagesAcknowledged, String failureCategory,
                                 String failureDetail) {}
