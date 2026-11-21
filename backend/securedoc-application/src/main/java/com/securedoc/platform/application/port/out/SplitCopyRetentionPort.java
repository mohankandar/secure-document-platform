package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Task 0028: split copies whose own retention period has run out. A split copy's clock starts when its destination
 * confirmed receipt (the derived delivery's delivered time); its period is the split destination's, else the
 * master's, else the tenant's redacted-copy retention. A copy whose export job is still open is never returned.
 */
public interface SplitCopyRetentionPort {

    /** Delivered split copies still stored whose period from receipt has run out by {@code now}; oldest first. */
    List<SplitCopy> findDueSplitCopies(Instant now, int limit);

    record SplitCopy(TenantId tenantId, PackageId packageId, UUID derivedDeliveryId,
                     IntegrationConnectionId destinationId, ArtifactId artifactId, Instant deliveredAt) { }

    static SplitCopyRetentionPort none() {
        return (now, limit) -> List.of();
    }
}
