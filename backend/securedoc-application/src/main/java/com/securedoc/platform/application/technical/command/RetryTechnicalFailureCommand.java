package com.securedoc.platform.application.technical.command;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Objects;

public record RetryTechnicalFailureCommand(
        PackageId packageId,
        TenantId tenantId,
        ActorId actorId,
        CorrelationId correlationId
) {
    public RetryTechnicalFailureCommand {
        Objects.requireNonNull(packageId);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(correlationId);
    }
}
