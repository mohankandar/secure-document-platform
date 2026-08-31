package com.securedoc.platform.application.intake.command;

import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.identity.CorrelationId;

import java.util.Map;
import java.util.Objects;

/** Command to create one intake batch (internally still represented by DocumentPackage). */
public record CreatePackageCommand(
        PackageType packageType,
        Map<String, String> businessIdentifiers,
        Map<String, String> metadata,
        String intakeFingerprint,
        String sourceReference,
        CorrelationId correlationId
) {
    public CreatePackageCommand {
        Objects.requireNonNull(packageType, "PackageType must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
        businessIdentifiers = businessIdentifiers == null ? Map.of() : Map.copyOf(businessIdentifiers);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        intakeFingerprint = normalizeOptional(intakeFingerprint);
        sourceReference = normalizeOptional(sourceReference);
    }

    public static CreatePackageCommand of(PackageType packageType, Map<String,String> businessIdentifiers,
                                          Map<String,String> metadata, CorrelationId correlationId) {
        return new CreatePackageCommand(packageType, businessIdentifiers, metadata, null, null, correlationId);
    }

    public static CreatePackageCommand watchedFolder(Map<String,String> businessIdentifiers, Map<String,String> metadata,
                                                     String fingerprint, String sourceReference,
                                                     CorrelationId correlationId) {
        return new CreatePackageCommand(PackageType.FOLDER_INTAKE, businessIdentifiers, metadata,
                fingerprint, sourceReference, correlationId);
    }

    /** Legacy convenience factory retained for non-API callers during migration. */
    public static CreatePackageCommand of(PackageType packageType, Map<String,String> metadata,
                                          CorrelationId correlationId) {
        return new CreatePackageCommand(packageType, Map.of(), metadata, null, null, correlationId);
    }

    private static String normalizeOptional(String value) {
        if (value == null) return null;
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }
}
