package com.securedoc.platform.domain.workflow;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Generic state machine for validating workflow transitions.
 * Define allowed transitions; then validate proposed transitions against the rules.
 *
 * @param <S> the state type (typically an enum)
 */
public final class WorkflowStateMachine<S> {

    private final Map<S, Set<S>> allowedTransitions;

    private WorkflowStateMachine(Map<S, Set<S>> allowedTransitions) {
        this.allowedTransitions = Collections.unmodifiableMap(new HashMap<>(allowedTransitions));
    }

    /**
     * Creates a new builder for defining state machine transitions.
     */
    public static <S> Builder<S> builder() {
        return new Builder<>();
    }

    /**
     * Returns true if the transition from the current state to the target state is allowed.
     */
    public boolean canTransition(S from, S to) {
        Objects.requireNonNull(from, "From state must not be null");
        Objects.requireNonNull(to, "To state must not be null");
        Set<S> targets = allowedTransitions.get(from);
        return targets != null && targets.contains(to);
    }

    /**
     * Validates a transition, throwing if not allowed.
     */
    public void validateTransition(S from, S to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException(
                    "Transition not allowed: " + from + " -> " + to);
        }
    }

    /**
     * Returns the set of states reachable from the given state.
     */
    public Set<S> allowedTargets(S from) {
        Set<S> targets = allowedTransitions.get(from);
        return targets != null ? Collections.unmodifiableSet(targets) : Set.of();
    }

    /**
     * Builder for constructing a WorkflowStateMachine with allowed transitions.
     */
    public static final class Builder<S> {

        private final Map<S, Set<S>> transitions = new HashMap<>();

        private Builder() {
        }

        /**
         * Allows transition from the source state to the given target states.
         */
        @SafeVarargs
        public final Builder<S> allow(S from, S... targets) {
            Objects.requireNonNull(from, "From state must not be null");
            Set<S> existing = transitions.computeIfAbsent(from, k -> new java.util.HashSet<>());
            for (S target : targets) {
                Objects.requireNonNull(target, "Target state must not be null");
                existing.add(target);
            }
            return this;
        }

        public WorkflowStateMachine<S> build() {
            return new WorkflowStateMachine<>(transitions);
        }
    }
}
