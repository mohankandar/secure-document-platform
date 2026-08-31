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

    Optional<DocumentPackage> findByIdAndTenantId(PackageId id, TenantId tenantId);

    List<DocumentPackage> findByTenantId(TenantId tenantId, int offset, int limit);

    long countByTenantId(TenantId tenantId);

    Optional<DocumentPackage> findByTenantIdAndIntakeFingerprint(TenantId tenantId, String fingerprint);

    /**
     * Internal/system work-discovery operation used by ProcessingWorker.
     * Returns packages with the given status for background processing.
     * After discovery, processing must continue using each package's own tenantId.
     */
    List<DocumentPackage> findByStatus(PackageStatus status, int limit);
}
