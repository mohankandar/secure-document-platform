package com.securedoc.platform.domain.delivery;

import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import java.time.Instant;
import java.util.UUID;

/** Immutable evidence for one delivery attempt, including the non-secret connection configuration version used. */
public record DeliveryAttempt(UUID id, UUID submissionId, int attemptNumber,
                              IntegrationConnectionId connectionId, Integer connectionConfigurationVersion,
                              DeliveryAttemptStatus status, String failureCategory,
                              Instant startedAt, Instant completedAt) {
    public DeliveryAttempt {
        if (id == null || submissionId == null || status == null || startedAt == null) throw new IllegalArgumentException("Delivery attempt identity/status/time are required");
        if (attemptNumber < 1) throw new IllegalArgumentException("attemptNumber must be >= 1");
    }
    public static DeliveryAttempt started(UUID submissionId, int attemptNumber, IntegrationConnectionId connectionId,
                                          Integer version, Instant now) {
        return new DeliveryAttempt(UUID.randomUUID(), submissionId, attemptNumber, connectionId, version,
                DeliveryAttemptStatus.STARTED, null, now, null);
    }
    public DeliveryAttempt completeSuccess(Instant now) { return new DeliveryAttempt(id, submissionId, attemptNumber, connectionId, connectionConfigurationVersion, DeliveryAttemptStatus.SUCCEEDED, null, startedAt, now); }
    public DeliveryAttempt completeFailure(String category, boolean retryable, Instant now) { return new DeliveryAttempt(id, submissionId, attemptNumber, connectionId, connectionConfigurationVersion, retryable ? DeliveryAttemptStatus.FAILED_RETRYABLE : DeliveryAttemptStatus.FAILED_ATTENTION, category, startedAt, now); }
}
