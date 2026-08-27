package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.intake.query.PackageDetail;
import com.securedoc.platform.domain.identity.PackageId;

/**
 * Inbound port for retrieving package details.
 */
public interface GetPackageQuery {

    PackageDetail execute(PackageId packageId, AuthenticatedPrincipal principal);
}
