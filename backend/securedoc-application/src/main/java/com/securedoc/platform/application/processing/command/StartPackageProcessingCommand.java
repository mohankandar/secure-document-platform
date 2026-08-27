package com.securedoc.platform.application.processing.command;

import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Objects;

/**
 * Command to initiate processing for a submitted package.
 * Transitions the package from SUBMITTED to PROCESSING and prepares
 * documents for extraction.
 */
public record StartPackageProcessingCommand(
        PackageId packageId,
        TenantId tenantId,
        CorrelationId correlationId
) {

    public StartPackageProcessingCommand {
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
    }

    public static StartPackageProcessingCommand of(PackageId packageId, TenantId tenantId,
                                                    CorrelationId correlationId) {
        return new StartPackageProcessingCommand(packageId, tenantId, correlationId);
    }
}
