package com.securedoc.platform.application.manual.command;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.FindingId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Objects;

public record RemoveManualRedactionRegionCommand(PackageId packageId, FindingId findingId,
                                                  TenantId tenantId, ActorId actorId,
                                                  CorrelationId correlationId) {
    public RemoveManualRedactionRegionCommand {
        Objects.requireNonNull(packageId);
        Objects.requireNonNull(findingId);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(correlationId);
    }
}
