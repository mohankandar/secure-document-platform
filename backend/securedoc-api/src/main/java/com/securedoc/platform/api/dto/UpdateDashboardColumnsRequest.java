package com.securedoc.platform.api.dto;

import java.util.List;

public record UpdateDashboardColumnsRequest(List<DashboardColumnInput> columns) {
    public record DashboardColumnInput(String key, boolean allowed, boolean defaultVisible) {}
}
