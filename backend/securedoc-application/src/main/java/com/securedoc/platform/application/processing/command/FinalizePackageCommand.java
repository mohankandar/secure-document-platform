package com.securedoc.platform.application.processing.command;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Objects;

/**
 * Command to finalize/approve a package after verifying all documents are safe.
 */
public record FinalizePackageCommand(
        PackageId packageId,
        TenantId tenantId,
        ActorId actorId,
        CorrelationId correlationId
) {
    public FinalizePackageCommand {
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(actorId, "ActorId must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
    }

    public static FinalizePackageCommand of(PackageId packageId, TenantId tenantId,
                                             ActorId actorId, CorrelationId correlationId) {
        return new FinalizePackageCommand(packageId, tenantId, actorId, correlationId);
    }
}
