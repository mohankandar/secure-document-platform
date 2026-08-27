package com.securedoc.platform.domain.review;

import com.securedoc.platform.domain.identity.ActorId;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable record of feedback provided during a review.
 */
public record FeedbackRecord(
        ActorId reviewerId,
        ReviewAction action,
        String comment,
        Instant timestamp
) {

    public FeedbackRecord {
        Objects.requireNonNull(reviewerId, "ReviewerId must not be null");
        Objects.requireNonNull(action, "Action must not be null");
        Objects.requireNonNull(timestamp, "Timestamp must not be null");
    }

    public static FeedbackRecord create(ActorId reviewerId, ReviewAction action, String comment) {
        return new FeedbackRecord(reviewerId, action, comment, Instant.now());
    }

    public boolean hasComment() {
        return comment != null && !comment.isBlank();
    }
}
