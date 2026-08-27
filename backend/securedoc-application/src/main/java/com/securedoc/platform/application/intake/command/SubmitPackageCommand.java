package com.securedoc.platform.application.intake.command;

import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;

import java.util.Objects;

/**
 * Command to submit a package for processing.
 */
public record SubmitPackageCommand(PackageId packageId, CorrelationId correlationId) {

    public SubmitPackageCommand {
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
    }

    public static SubmitPackageCommand of(PackageId packageId, CorrelationId correlationId) {
        return new SubmitPackageCommand(packageId, correlationId);
    }

    /**
     * Convenience factory for non-API callers that do not already have a correlation id.
     */
    public static SubmitPackageCommand of(PackageId packageId) {
        return new SubmitPackageCommand(packageId, CorrelationId.generate());
    }
}
