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

    /**
     * Task 0039: locks the case until the current transaction ends, so a check on its files and the file added after it
     * can't interleave with another upload to the same case. Call it before the case is read. Without a transaction
     * there is nothing to hold the lock, and it does nothing. The default does nothing (in-memory copies used in tests
     * have no concurrent writers).
     */
    default void lockForUpdate(PackageId id, TenantId tenantId) {
    }

    /**
     * Task 0071: saves the case's new status only while the stored one is still among {@code from}; false when another
     * person's decision (a rejection, an approval) came first. Decided in the database, so a decision that read the case
     * earlier can't overwrite a newer one. The default, for in-memory copies in tests, saves.
     */
    default boolean changeStatus(DocumentPackage documentPackage, java.util.Set<PackageStatus> from) {
        save(documentPackage);
        return true;
    }

    List<DocumentPackage> findByTenantId(TenantId tenantId, int offset, int limit);

    /** Task 0111: the cases the dashboard lists (none it hides), oldest first, in an order stable across pages. */
    default List<DocumentPackage> findDashboardCases(TenantId tenantId, int offset, int limit) {
        return findByTenantId(tenantId, offset, limit);
    }

    long countByTenantId(TenantId tenantId);

    Optional<DocumentPackage> findByTenantIdAndIntakeFingerprint(TenantId tenantId, String fingerprint);

    /**
     * Internal/system work-discovery operation used by ProcessingWorker.
     * Returns packages with the given status for background processing.
     * After discovery, processing must continue using each package's own tenantId.
     */
    List<DocumentPackage> findByStatus(PackageStatus status, int limit);
}
