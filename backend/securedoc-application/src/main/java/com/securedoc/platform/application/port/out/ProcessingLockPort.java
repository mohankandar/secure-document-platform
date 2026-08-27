package com.securedoc.platform.application.port.out;

import java.time.Duration;
import java.util.Optional;

/**
 * Outbound port for acquiring processing locks to ensure
 * a document/package is not processed concurrently by multiple workers.
 */
public interface ProcessingLockPort {

    /**
     * Attempts to acquire a lock for the given resource.
     *
     * @param resourceKey unique key identifying the resource to lock
     * @param ttl         maximum lock duration
     * @return lock token if acquired, empty if already held
     */
    Optional<String> tryAcquire(String resourceKey, Duration ttl);

    /**
     * Releases a previously acquired lock.
     *
     * @param resourceKey the resource key
     * @param lockToken   token returned by tryAcquire
     */
    void release(String resourceKey, String lockToken);

    /**
     * Checks if a lock is currently held for the resource.
     */
    boolean isLocked(String resourceKey);
}
