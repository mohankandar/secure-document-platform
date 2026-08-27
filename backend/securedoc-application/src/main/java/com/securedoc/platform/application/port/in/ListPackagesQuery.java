package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.intake.query.PackageSummary;

import java.util.List;

/**
 * Inbound port for listing packages belonging to the current tenant.
 */
public interface ListPackagesQuery {

    List<PackageSummary> execute(AuthenticatedPrincipal principal, int offset, int limit);
}
