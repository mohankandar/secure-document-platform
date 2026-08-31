package com.securedoc.platform.application.port.out;

import com.securedoc.platform.application.operations.model.DashboardPage;
import com.securedoc.platform.application.operations.model.DashboardStage;
import com.securedoc.platform.application.operations.model.OperationalReportSnapshot;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;

/** Read-model port for the shared operational dashboard and compact V1 reports. */
public interface OperationsReadPort {
    DashboardPage loadDashboard(TenantId tenantId, DashboardStage stage, String search,
                                int offset, int limit, String sortField, boolean descending);

    OperationalReportSnapshot loadReports(TenantId tenantId, Instant now);
}
