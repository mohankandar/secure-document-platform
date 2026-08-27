package com.securedoc.platform.domain.workflow;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Ordered timeline of events for a specific entity (package, document, etc.).
 * Provides full auditability of state transitions and actions.
 */
public final class WorkflowTimeline {

    private final String entityType;
    private final String entityId;
    private final List<TimelineEntry> entries;

    private WorkflowTimeline(String entityType, String entityId) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.entries = new ArrayList<>();
    }

    public static WorkflowTimeline create(String entityType, String entityId) {
        Objects.requireNonNull(entityType, "Entity type must not be null");
        Objects.requireNonNull(entityId, "Entity ID must not be null");
        return new WorkflowTimeline(entityType, entityId);
    }

    public static WorkflowTimeline reconstitute(String entityType, String entityId, List<TimelineEntry> entries) {
        WorkflowTimeline timeline = new WorkflowTimeline(entityType, entityId);
        timeline.entries.addAll(entries);
        return timeline;
    }

    /**
     * Records a state transition in the timeline.
     */
    public void recordTransition(String fromState, String toState, ActorId actorId,
                                  CorrelationId correlationId, String detail) {
        Objects.requireNonNull(fromState, "From state must not be null");
        Objects.requireNonNull(toState, "To state must not be null");
        entries.add(TimelineEntry.of("STATE_TRANSITION", fromState, toState, actorId, correlationId, detail));
    }

    /**
     * Records a generic action in the timeline.
     */
    public void recordAction(String action, ActorId actorId, CorrelationId correlationId, String detail) {
        entries.add(TimelineEntry.of(action, null, null, actorId, correlationId, detail));
    }

    /**
     * Returns the most recent entry, or empty if timeline is empty.
     */
    public Optional<TimelineEntry> lastEntry() {
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(entries.get(entries.size() - 1));
    }

    /**
     * Returns all entries after the given timestamp.
     */
    public List<TimelineEntry> entriesAfter(Instant after) {
        return entries.stream()
                .filter(e -> e.timestamp().isAfter(after))
                .toList();
    }

    public List<TimelineEntry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public String entityType() {
        return entityType;
    }

    public String entityId() {
        return entityId;
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
