package com.securedoc.platform.application.intake.polling;

import com.securedoc.platform.domain.integration.PollingRunStatus;
import com.securedoc.platform.domain.integration.PollingRunTrigger;

import java.time.Instant;
import java.util.UUID;

public record PollingRunSnapshot(UUID runId, UUID connectionId, PollingRunTrigger trigger, PollingRunStatus status,
                                 Instant startedAt, Instant completedAt, int packagesDiscovered, int documentsExpected,
                                 int documentsRetrieved, int packagesAcknowledged, String failureCategory, String failureDetail) {}
