package com.securedoc.platform.domain.identity;

import java.util.Objects;
import java.util.UUID;

public record BusinessRecordId(UUID value) {
    public BusinessRecordId {
        Objects.requireNonNull(value, "BusinessRecordId value must not be null");
    }
    public static BusinessRecordId generate() { return new BusinessRecordId(UUID.randomUUID()); }
    public static BusinessRecordId of(UUID value) { return new BusinessRecordId(value); }
    public static BusinessRecordId fromString(String value) { return new BusinessRecordId(UUID.fromString(value)); }
    @Override public String toString() { return value.toString(); }
}
