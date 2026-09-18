package com.securedoc.platform.infrastructure.lock;

import com.securedoc.platform.application.port.out.ProcessingLockPort;
import com.securedoc.platform.infrastructure.persistence.entity.ProcessingLockEntity;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataProcessingLockRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Database-backed processing lock using INSERT/DELETE semantics.
 * A lock is active if it exists and has not expired.
 * Expired locks are reclaimed by new acquirers.
 */
@Component
public class DatabaseProcessingLockAdapter implements ProcessingLockPort {

    private final SpringDataProcessingLockRepository lockRepo;

    public DatabaseProcessingLockAdapter(SpringDataProcessingLockRepository lockRepo) {
        this.lockRepo = lockRepo;
    }

    @Override
    @Transactional
    public Optional<String> tryAcquire(String resourceKey, Duration ttl) {
        Instant now = Instant.now();

        // Check if an active (non-expired) lock exists
        Optional<ProcessingLockEntity> existing = lockRepo.findActiveLock(resourceKey, now);
        if (existing.isPresent()) {
            return Optional.empty(); // Lock is held
        }

        // Clean up any expired lock for this key
        lockRepo.deleteById(resourceKey);

        // Create new lock
        String fencingToken = UUID.randomUUID().toString();
        ProcessingLockEntity lock = new ProcessingLockEntity();
        lock.setLockKey(resourceKey);
        lock.setOwnerId(fencingToken);
        lock.setFencingToken(fencingToken);
        lock.setExpiresAt(now.plus(ttl));
        lock.setCreatedAt(now);
        lock.setUpdatedAt(now);

        try {
            lockRepo.save(lock);
            return Optional.of(fencingToken);
        } catch (Exception e) {
            // Concurrent insert — lock already taken
            return Optional.empty();
        }
    }

    @Override
    @Transactional
    public void release(String resourceKey, String lockToken) {
        lockRepo.deleteByLockKeyAndFencingToken(resourceKey, lockToken);
    }

    @Override
    @Transactional
    public void forceRelease(String resourceKey) {
        lockRepo.deleteById(resourceKey);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isLocked(String resourceKey) {
        return lockRepo.findActiveLock(resourceKey, Instant.now()).isPresent();
    }
}
