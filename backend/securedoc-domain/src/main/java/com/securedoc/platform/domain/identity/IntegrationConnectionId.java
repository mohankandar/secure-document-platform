package com.securedoc.platform.domain.identity;

import java.util.Objects;
import java.util.UUID;

public record IntegrationConnectionId(UUID value) {
    public IntegrationConnectionId {
        Objects.requireNonNull(value, "IntegrationConnectionId value must not be null");
    }

    public static IntegrationConnectionId generate() { return new IntegrationConnectionId(UUID.randomUUID()); }
    public static IntegrationConnectionId of(UUID value) { return new IntegrationConnectionId(value); }
    public static IntegrationConnectionId fromString(String value) { return new IntegrationConnectionId(UUID.fromString(value)); }
    @Override public String toString() { return value.toString(); }
}
