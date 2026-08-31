package com.securedoc.platform.api.dto;

import java.util.List;

public record OperationsDashboardResponse(DashboardOverviewResponse overview,
                                          List<DashboardColumnResponse> columns,
                                          List<DashboardItemResponse> items,
                                          long totalItems, int offset, int limit) {}
