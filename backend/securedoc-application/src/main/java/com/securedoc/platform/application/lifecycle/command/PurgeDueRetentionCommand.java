package com.securedoc.platform.application.lifecycle.command;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;

import java.time.Instant;
import java.util.Objects;

public record PurgeDueRetentionCommand(Instant now, int limit, ActorId actorId, CorrelationId correlationId) {
    public PurgeDueRetentionCommand {
        Objects.requireNonNull(now); Objects.requireNonNull(actorId); Objects.requireNonNull(correlationId);
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be between 1 and 500");
    }
}
