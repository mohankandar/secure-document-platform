package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.List;
import java.util.Optional;

/**
 * Outbound port for persisting and querying document packages.
 */
public interface PackageRepository {

    void save(DocumentPackage documentPackage);

    Optional<DocumentPackage> findById(PackageId id);

    Optional<DocumentPackage> findByIdAndTenantId(PackageId id, TenantId tenantId);

    List<DocumentPackage> findByTenantId(TenantId tenantId, int offset, int limit);

    long countByTenantId(TenantId tenantId);

    /**
     * Finds packages with the given status, up to the specified limit.
     * Used by the processing worker to discover submitted packages.
     */
    List<DocumentPackage> findByStatus(PackageStatus status, int limit);
}
