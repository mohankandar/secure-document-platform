package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.operations.model.OperationalReportSnapshot;
import com.securedoc.platform.application.operations.model.ReportScope;

import java.time.Instant;

public interface GetOperationalReportsQuery {
    OperationalReportSnapshot execute(AuthenticatedPrincipal principal, Instant from, Instant to,
                                      String source, String outcome);

    /** Task 0081: as above, of one source system or one sensitivity level. */
    default OperationalReportSnapshot execute(AuthenticatedPrincipal principal, Instant from, Instant to,
                                              String source, String outcome, ReportScope scope) {
        if (ReportScope.EVERYTHING.equals(scope)) return execute(principal, from, to, source, outcome);
        throw new UnsupportedOperationException("No report by source system or level");
    }
}
