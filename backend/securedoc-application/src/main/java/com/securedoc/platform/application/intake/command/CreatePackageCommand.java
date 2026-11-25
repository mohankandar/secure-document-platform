package com.securedoc.platform.application.intake.command;

import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.document.SensitivityLevel;
import com.securedoc.platform.domain.identity.CorrelationId;

import java.util.Map;
import java.util.Objects;

/**
 * Command to create one intake batch (internally still represented by DocumentPackage).
 *
 * <p>{@code caseSensitivity} is the case level as the source system sent it (task 0021): nothing sent is Standard,
 * and an unknown value is taken as Standard with {@code unknown} set, so the service records LV-INT-005.
 */
public record CreatePackageCommand(
        PackageType packageType,
        Map<String, String> businessIdentifiers,
        Map<String, String> metadata,
        String intakeFingerprint,
        String sourceReference,
        CorrelationId correlationId,
        SensitivityLevel.Received caseSensitivity,
        /* Task 0073: as sent; null when none was, then the source's default (sourceKey), else Normal. */
        com.securedoc.platform.domain.pipeline.PipelinePriority priority,
        String sourceKey
) {
    public CreatePackageCommand {
        Objects.requireNonNull(packageType, "PackageType must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
        businessIdentifiers = businessIdentifiers == null ? Map.of() : Map.copyOf(businessIdentifiers);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        intakeFingerprint = normalizeOptional(intakeFingerprint);
        sourceReference = normalizeOptional(sourceReference);
        caseSensitivity = caseSensitivity == null ? SensitivityLevel.Received.standard() : caseSensitivity;
    }

    /** A command without a priority or a source default. */
    public CreatePackageCommand(PackageType packageType, Map<String, String> businessIdentifiers,
                                Map<String, String> metadata, String intakeFingerprint, String sourceReference,
                                CorrelationId correlationId, SensitivityLevel.Received caseSensitivity) {
        this(packageType, businessIdentifiers, metadata, intakeFingerprint, sourceReference, correlationId,
                caseSensitivity, null, null);
    }

    /** A command with no level sent (Standard). */
    public CreatePackageCommand(PackageType packageType, Map<String, String> businessIdentifiers,
                                Map<String, String> metadata, String intakeFingerprint, String sourceReference,
                                CorrelationId correlationId) {
        this(packageType, businessIdentifiers, metadata, intakeFingerprint, sourceReference, correlationId,
                SensitivityLevel.Received.standard());
    }

    public static CreatePackageCommand of(PackageType packageType, Map<String,String> businessIdentifiers,
                                          Map<String,String> metadata, CorrelationId correlationId) {
        return new CreatePackageCommand(packageType, businessIdentifiers, metadata, null, null, correlationId);
    }

    public static CreatePackageCommand watchedFolder(Map<String,String> businessIdentifiers, Map<String,String> metadata,
                                                     String fingerprint, String sourceReference,
                                                     CorrelationId correlationId) {
        return watchedFolder(businessIdentifiers, metadata, fingerprint, sourceReference, correlationId,
                SensitivityLevel.Received.standard());
    }

    /** A watched-folder batch with the folder's level (task 0021). */
    public static CreatePackageCommand watchedFolder(Map<String,String> businessIdentifiers, Map<String,String> metadata,
                                                     String fingerprint, String sourceReference,
                                                     CorrelationId correlationId,
                                                     SensitivityLevel.Received caseSensitivity) {
        return new CreatePackageCommand(PackageType.FOLDER_INTAKE, businessIdentifiers, metadata,
                fingerprint, sourceReference, correlationId, caseSensitivity);
    }

    /** Legacy convenience factory retained for non-API callers during migration. */
    public static CreatePackageCommand of(PackageType packageType, Map<String,String> metadata,
                                          CorrelationId correlationId) {
        return new CreatePackageCommand(packageType, Map.of(), metadata, null, null, correlationId);
    }

    /** The same command with the case level the source sent. */
    public CreatePackageCommand withCaseSensitivity(SensitivityLevel.Received received) {
        return new CreatePackageCommand(packageType, businessIdentifiers, metadata, intakeFingerprint,
                sourceReference, correlationId, received, priority, sourceKey);
    }

    /** The same command with the priority sent (null when none) and the source whose default holds otherwise. */
    public CreatePackageCommand withPriority(com.securedoc.platform.domain.pipeline.PipelinePriority sent, String source) {
        return new CreatePackageCommand(packageType, businessIdentifiers, metadata, intakeFingerprint,
                sourceReference, correlationId, caseSensitivity, sent, source);
    }

    private static String normalizeOptional(String value) {
        if (value == null) return null;
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }
}
