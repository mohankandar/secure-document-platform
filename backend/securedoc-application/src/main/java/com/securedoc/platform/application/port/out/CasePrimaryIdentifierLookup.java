package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Collection;
import java.util.Map;

/**
 * Read-only: the case's main ID (the tenant's primary case field, for example the loan number) for each package, so
 * people see "LN204812" instead of a package id. A package with no main ID value is left out of the answer.
 */
public interface CasePrimaryIdentifierLookup {
    Map<PackageId, CasePrimaryIdentifier> findByPackageIds(TenantId tenantId, Collection<PackageId> packageIds);

    record CasePrimaryIdentifier(String label, String value) {}
}
