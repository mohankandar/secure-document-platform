package com.securedoc.platform.domain.identity;

import java.util.Objects;
import java.util.UUID;

/**
 * Unique identifier for an actor (user or system) performing actions.
 */
public record ActorId(UUID value) {

    public ActorId {
        Objects.requireNonNull(value, "ActorId value must not be null");
    }

    public static ActorId generate() {
        return new ActorId(UUID.randomUUID());
    }

    public static ActorId of(UUID value) {
        return new ActorId(value);
    }

    public static ActorId fromString(String value) {
        Objects.requireNonNull(value, "ActorId string must not be null");
        return new ActorId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
