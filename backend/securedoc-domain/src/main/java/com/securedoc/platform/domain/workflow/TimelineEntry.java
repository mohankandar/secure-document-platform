package com.securedoc.platform.domain.workflow;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable record of an event in a workflow timeline.
 * Captures what happened, who did it, and when.
 */
public record TimelineEntry(
        String action,
        String fromState,
        String toState,
        ActorId actorId,
        CorrelationId correlationId,
        String detail,
        Instant timestamp
) {

    public TimelineEntry {
        Objects.requireNonNull(action, "Action must not be null");
        Objects.requireNonNull(actorId, "ActorId must not be null");
        Objects.requireNonNull(timestamp, "Timestamp must not be null");
        if (action.isBlank()) {
            throw new IllegalArgumentException("Action must not be blank");
        }
    }

    public static TimelineEntry of(String action, String fromState, String toState,
                                    ActorId actorId, CorrelationId correlationId, String detail) {
        return new TimelineEntry(action, fromState, toState, actorId, correlationId, detail, Instant.now());
    }

    public static TimelineEntry simple(String action, ActorId actorId) {
        return new TimelineEntry(action, null, null, actorId, null, null, Instant.now());
    }

    public Optional<String> fromStateOpt() {
        return Optional.ofNullable(fromState);
    }

    public Optional<String> toStateOpt() {
        return Optional.ofNullable(toState);
    }

    public Optional<CorrelationId> correlationIdOpt() {
        return Optional.ofNullable(correlationId);
    }

    public Optional<String> detailOpt() {
        return Optional.ofNullable(detail);
    }

    public boolean isStateTransition() {
        return fromState != null && toState != null;
    }
}
