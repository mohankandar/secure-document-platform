package com.securedoc.platform.application.lifecycle.command;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Objects;

public record ApproveFinalDeliveryCommand(PackageId packageId, TenantId tenantId, ActorId actorId, CorrelationId correlationId,
                                          /** Task 0076: names the bulk action in the audit record; null for one case. */
                                          String bulkAction) {
    public ApproveFinalDeliveryCommand(PackageId packageId, TenantId tenantId, ActorId actorId, CorrelationId correlationId) {
        this(packageId, tenantId, actorId, correlationId, null);
    }

    public ApproveFinalDeliveryCommand {
        Objects.requireNonNull(packageId); Objects.requireNonNull(tenantId); Objects.requireNonNull(actorId); Objects.requireNonNull(correlationId);
    }
}
