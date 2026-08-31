package com.securedoc.platform.application.lifecycle.command;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Objects;

public record ExtendTechnicalHoldCommand(PackageId packageId, TenantId tenantId, ActorId actorId,
                                         CorrelationId correlationId, int holdHoursFromNow) {
    public ExtendTechnicalHoldCommand {
        Objects.requireNonNull(packageId); Objects.requireNonNull(tenantId); Objects.requireNonNull(actorId); Objects.requireNonNull(correlationId);
    }
}
