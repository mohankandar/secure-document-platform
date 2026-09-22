package com.securedoc.platform.application.processing.command;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Objects;

/**
 * Command to trigger processing for a submitted/active package.
 *
 * <p>The recovery flag is internal-only. It is set by the distributed recovery scheduler after
 * the prior package-processing lease has expired and authorizes cleanup/replay of interrupted
 * in-flight document work from the retained original.
 */
public record TriggerPackageProcessingCommand(
        PackageId packageId,
        TenantId tenantId,
        ActorId actorId,
        CorrelationId correlationId,
        boolean recovery,
        boolean resetAttemptBudget
) {
    public TriggerPackageProcessingCommand {
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(actorId, "ActorId must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
    }

    public static TriggerPackageProcessingCommand of(PackageId packageId, TenantId tenantId,
                                                      ActorId actorId, CorrelationId correlationId) {
        return new TriggerPackageProcessingCommand(packageId, tenantId, actorId, correlationId, false, false);
    }

    /**
     * Starts an explicitly requested fresh processing/redrive cycle. This is distinct from a normal
     * continuation after review/final approval: a fresh cycle intentionally receives a new automatic
     * recovery budget beginning at attempt 1.
     */
    public static TriggerPackageProcessingCommand freshCycle(PackageId packageId, TenantId tenantId,
                                                              ActorId actorId, CorrelationId correlationId) {
        return new TriggerPackageProcessingCommand(packageId, tenantId, actorId, correlationId, false, true);
    }

    public static TriggerPackageProcessingCommand recovery(PackageId packageId, TenantId tenantId,
                                                            ActorId actorId, CorrelationId correlationId) {
        return new TriggerPackageProcessingCommand(packageId, tenantId, actorId, correlationId, true, false);
    }
}
