package com.securedoc.platform.application.export.command;

import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Objects;

public record ExportPackageDocumentsCommand(
        PackageId packageId,
        TenantId tenantId,
        CorrelationId correlationId
) {
    public ExportPackageDocumentsCommand {
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
    }

    public static ExportPackageDocumentsCommand of(PackageId packageId, TenantId tenantId,
                                                    CorrelationId correlationId) {
        return new ExportPackageDocumentsCommand(packageId, tenantId, correlationId);
    }
}
