package com.securedoc.platform.application.intake.command;

import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.identity.CorrelationId;

import java.util.Map;
import java.util.Objects;

/**
 * Command to create a new document package.
 */
public record CreatePackageCommand(
        PackageType packageType,
        Map<String, String> metadata,
        CorrelationId correlationId
) {

    public CreatePackageCommand {
        Objects.requireNonNull(packageType, "PackageType must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
        if (metadata == null) {
            metadata = Map.of();
        } else {
            metadata = Map.copyOf(metadata);
        }
    }

    public static CreatePackageCommand of(PackageType packageType, Map<String, String> metadata,
                                          CorrelationId correlationId) {
        return new CreatePackageCommand(packageType, metadata, correlationId);
    }

    /**
     * Convenience factory for non-API callers that do not already have a correlation id.
     */
    public static CreatePackageCommand of(PackageType packageType, Map<String, String> metadata) {
        return new CreatePackageCommand(packageType, metadata, CorrelationId.generate());
    }

    public static CreatePackageCommand manualUpload(Map<String, String> metadata, CorrelationId correlationId) {
        return new CreatePackageCommand(PackageType.MANUAL_UPLOAD, metadata, correlationId);
    }

    /**
     * Convenience factory for non-API callers that do not already have a correlation id.
     */
    public static CreatePackageCommand manualUpload(Map<String, String> metadata) {
        return new CreatePackageCommand(PackageType.MANUAL_UPLOAD, metadata, CorrelationId.generate());
    }
}
