package com.securedoc.platform.application.manual.command;

import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Objects;

public record AddManualRedactionRegionCommand(
        PackageId packageId, DocumentId documentId, int pageNumber, EntityType entityType,
        double left, double top, double width, double height,
        TenantId tenantId, ActorId actorId, CorrelationId correlationId) {
    public AddManualRedactionRegionCommand {
        Objects.requireNonNull(packageId);
        Objects.requireNonNull(documentId);
        Objects.requireNonNull(entityType);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(correlationId);
    }
}
