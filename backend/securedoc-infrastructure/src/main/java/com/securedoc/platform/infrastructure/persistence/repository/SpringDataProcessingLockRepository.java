package com.securedoc.platform.infrastructure.persistence.repository;

import com.securedoc.platform.infrastructure.persistence.entity.ProcessingLockEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface SpringDataProcessingLockRepository extends JpaRepository<ProcessingLockEntity, String> {

    @Modifying
    @Query("DELETE FROM ProcessingLockEntity e WHERE e.lockKey = :lockKey AND e.fencingToken = :token")
    int deleteByLockKeyAndFencingToken(@Param("lockKey") String lockKey, @Param("token") String token);

    @Query("SELECT e FROM ProcessingLockEntity e WHERE e.lockKey = :lockKey AND e.expiresAt > :now")
    Optional<ProcessingLockEntity> findActiveLock(@Param("lockKey") String lockKey, @Param("now") Instant now);
}
