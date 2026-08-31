package com.securedoc.platform.domain.identity;

import java.util.Objects;
import java.util.UUID;

public record BusinessIdentifierDefinitionId(UUID value) {
    public BusinessIdentifierDefinitionId {
        Objects.requireNonNull(value, "BusinessIdentifierDefinitionId value must not be null");
    }
    public static BusinessIdentifierDefinitionId generate() { return new BusinessIdentifierDefinitionId(UUID.randomUUID()); }
    public static BusinessIdentifierDefinitionId of(UUID value) { return new BusinessIdentifierDefinitionId(value); }
    @Override public String toString() { return value.toString(); }
}
