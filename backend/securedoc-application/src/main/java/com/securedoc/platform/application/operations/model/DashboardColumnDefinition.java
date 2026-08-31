package com.securedoc.platform.application.operations.model;

import java.util.Objects;

/** Column the tenant allows the shared business dashboard to present. */
public record DashboardColumnDefinition(
        String key,
        String label,
        String kind,
        boolean primary,
        boolean searchable,
        boolean defaultVisible,
        int displayOrder
) {
    public DashboardColumnDefinition {
        Objects.requireNonNull(key);
        Objects.requireNonNull(label);
        Objects.requireNonNull(kind);
    }
}
