package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.operations.model.DashboardPage;
import com.securedoc.platform.application.operations.model.DashboardStage;

public interface GetOperationsDashboardQuery {
    DashboardPage execute(AuthenticatedPrincipal principal, DashboardStage stage, String search,
                          int offset, int limit, String sortField, boolean descending);
}
