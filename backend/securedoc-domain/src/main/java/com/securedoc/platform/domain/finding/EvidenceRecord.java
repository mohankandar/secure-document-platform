package com.securedoc.platform.domain.finding;

import com.securedoc.platform.domain.identity.ActorId;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable record of evidence supporting a finding status change.
 * Captures who/what made the decision and why.
 */
public record EvidenceRecord(
        ActorId actorId,
        FindingStatus fromStatus,
        FindingStatus toStatus,
        String reason,
        Instant timestamp
) {

    public EvidenceRecord {
        Objects.requireNonNull(actorId, "ActorId must not be null");
        Objects.requireNonNull(fromStatus, "From status must not be null");
        Objects.requireNonNull(toStatus, "To status must not be null");
        Objects.requireNonNull(timestamp, "Timestamp must not be null");
    }

    public static EvidenceRecord create(ActorId actorId, FindingStatus from, FindingStatus to, String reason) {
        return new EvidenceRecord(actorId, from, to, reason, Instant.now());
    }

    public boolean hasReason() {
        return reason != null && !reason.isBlank();
    }
}
