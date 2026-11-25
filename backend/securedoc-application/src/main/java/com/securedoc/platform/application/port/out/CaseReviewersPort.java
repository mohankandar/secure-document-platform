package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Collection;
import java.util.Set;

/** Task 0076: the four-eyes rule needs to know who reviewed a case. */
public interface CaseReviewersPort {
    /** Of these cases, the ones this person took a review decision in, in any processing run. */
    Set<PackageId> reviewedBy(TenantId tenantId, ActorId person, Collection<PackageId> cases);
}
