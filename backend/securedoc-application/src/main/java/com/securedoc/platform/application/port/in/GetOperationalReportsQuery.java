package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.operations.model.OperationalReportSnapshot;

public interface GetOperationalReportsQuery {
    OperationalReportSnapshot execute(AuthenticatedPrincipal principal);
}
