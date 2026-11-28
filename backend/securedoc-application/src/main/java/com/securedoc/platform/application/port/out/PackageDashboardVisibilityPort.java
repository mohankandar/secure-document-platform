package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;

/** Persists operational dashboard visibility without changing package lifecycle status. */
public interface PackageDashboardVisibilityPort {
    void hide(PackageId packageId, TenantId tenantId, ActorId actorId, Instant hiddenAt);

    /** Task 0104: a case hidden after its files were deleted shows again when its next batch arrives. */
    default void show(PackageId packageId, TenantId tenantId) {
    }
}
