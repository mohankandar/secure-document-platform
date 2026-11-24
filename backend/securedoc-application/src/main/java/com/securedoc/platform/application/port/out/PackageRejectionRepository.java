package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.review.RejectionReason;

import java.time.Instant;
import java.util.Optional;

/** Task 0071: the reason of a rejected case, once per case (a rejection can't be undone). */
public interface PackageRejectionRepository {
    void save(TenantId tenantId, PackageId packageId, RejectionReason reason, ActorId rejectedBy, Instant at);

    Optional<RejectionReason> find(TenantId tenantId, PackageId packageId);
}
