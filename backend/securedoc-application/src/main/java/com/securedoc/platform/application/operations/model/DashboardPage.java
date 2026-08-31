package com.securedoc.platform.application.operations.model;

import java.util.List;

public record DashboardPage(
        DashboardOverview overview,
        List<DashboardColumnDefinition> columns,
        List<DashboardItem> items,
        long totalItems,
        int offset,
        int limit
) {
    public DashboardPage {
        columns = columns == null ? List.of() : List.copyOf(columns);
        items = items == null ? List.of() : List.copyOf(items);
    }
}
