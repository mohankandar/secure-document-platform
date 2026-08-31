package com.securedoc.platform.application.operations.model;

import java.util.Objects;

public record DashboardIdentifierValue(String key, String label, String value, boolean primary, int displayOrder) {
    public DashboardIdentifierValue {
        Objects.requireNonNull(key);
        Objects.requireNonNull(label);
        value = value == null ? "" : value;
    }
}
