package com.securedoc.platform.api.processing;

import com.securedoc.platform.application.processing.command.TriggerPackageProcessingCommand;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Optional;

/** Process-local facade over the distributed package-processing lease. */
public interface PackageProcessingDispatchLease {

    Optional<Claim> tryClaim(TriggerPackageProcessingCommand command);

    /**
     * Fail-closed freshness check used immediately before queued work begins. Implementations backed
     * by a distributed lease must reject a stale fencing generation after another node takes over.
     */
    default boolean isCurrent(Claim claim) {
        return true;
    }

    void release(Claim claim);

    record Claim(PackageId packageId,
                 TenantId tenantId,
                 String ownerId,
                 long fencingToken,
                 boolean coordinated) {
        static Claim singleNode(PackageId packageId, TenantId tenantId) {
            return new Claim(packageId, tenantId, "single-node", 0L, false);
        }
    }

    static PackageProcessingDispatchLease disabled() {
        return new PackageProcessingDispatchLease() {
            @Override
            public Optional<Claim> tryClaim(TriggerPackageProcessingCommand command) {
                return Optional.of(Claim.singleNode(command.packageId(), command.tenantId()));
            }

            @Override
            public void release(Claim claim) {
                // no-op
            }
        };
    }
}
