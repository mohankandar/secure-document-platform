package com.securedoc.platform.infrastructure.persistence.repository;

import com.securedoc.platform.infrastructure.persistence.entity.DocumentPackageEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataDocumentPackageRepository extends JpaRepository<DocumentPackageEntity, UUID> {

    Optional<DocumentPackageEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * Task 0039: a row lock on the case, held until the caller's transaction ends (which it must have). Returns the id
     * when the case exists for this tenant. Only the id is read, so no entity is loaded before the lock is held.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = "SELECT CAST(id AS varchar) FROM document_packages WHERE id = :id AND tenant_id = :tenantId FOR UPDATE",
            nativeQuery = true)
    Optional<String> lockByIdAndTenantId(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    /** Task 0071: see PackageRepository#changeStatus. The version goes up, so an older copy can't be saved after it. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update DocumentPackageEntity p set p.status = :to, p.updatedAt = :at, p.version = p.version + 1 "
            + "where p.id = :id and p.tenantId = :tenantId and p.status in :from")
    int changeStatus(@Param("id") UUID id, @Param("tenantId") UUID tenantId, @Param("to") String to,
                     @Param("at") java.time.Instant at, @Param("from") java.util.Collection<String> from);

    List<DocumentPackageEntity> findByTenantId(UUID tenantId, Pageable pageable);

    /** Task 0111: the cases the dashboard lists: none it hides (dashboard_hidden_at set), oldest first, stable across pages. */
    List<DocumentPackageEntity> findByTenantIdAndDashboardHiddenAtIsNullOrderByCreatedAtAscIdAsc(UUID tenantId, Pageable pageable);

    /** Task 0111: cases still at the given status, not hidden, opened before the cut-off (all entities), oldest first. */
    List<DocumentPackageEntity> findByStatusAndCreatedAtBeforeAndDashboardHiddenAtIsNullOrderByCreatedAtAsc(String status, java.time.Instant before, Pageable pageable);

    long countByTenantId(UUID tenantId);

    Optional<DocumentPackageEntity> findByTenantIdAndIntakeFingerprint(UUID tenantId, String intakeFingerprint);

    List<DocumentPackageEntity> findByStatus(String status, Pageable pageable);
}
