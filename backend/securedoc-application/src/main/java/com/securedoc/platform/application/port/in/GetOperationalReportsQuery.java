package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.operations.model.OperationalReportSnapshot;

import java.time.Instant;

public interface GetOperationalReportsQuery {
    OperationalReportSnapshot execute(AuthenticatedPrincipal principal, Instant from, Instant to,
                                      String source, String outcome);
}
