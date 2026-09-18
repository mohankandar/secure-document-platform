package com.securedoc.platform.domain.storage;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable versioned filesystem configuration used only for LexaVault processing artifacts. */
public record ProcessingStorageProvider(
        UUID id,
        String name,
        ProcessingStorageProviderType providerType,
        String rootPath,
        ProcessingStorageProviderStatus status,
        int configurationVersion,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public ProcessingStorageProvider {
        Objects.requireNonNull(id, "Processing storage provider id is required");
        name = requireText(name, "Processing storage provider name", 120);
        Objects.requireNonNull(providerType, "Processing storage provider type is required");
        rootPath = requireText(rootPath, "Processing storage root path", 2048);
        Objects.requireNonNull(status, "Processing storage provider status is required");
        if (configurationVersion < 1) throw new IllegalArgumentException("Configuration version must be >= 1");
        Objects.requireNonNull(createdAt, "Created time is required");
        Objects.requireNonNull(updatedAt, "Updated time is required");
    }

    public static ProcessingStorageProvider create(String name, ProcessingStorageProviderType providerType,
                                                   String rootPath, UUID createdBy, Instant now) {
        return create(name, providerType, rootPath, 1, createdBy, now);
    }

    public static ProcessingStorageProvider create(String name, ProcessingStorageProviderType providerType,
                                                   String rootPath, int configurationVersion, UUID createdBy, Instant now) {
        return new ProcessingStorageProvider(UUID.randomUUID(), name, providerType, rootPath,
                ProcessingStorageProviderStatus.CANDIDATE, configurationVersion, createdBy, now, now);
    }

    public ProcessingStorageProvider markAvailable(Instant now) {
        if (status == ProcessingStorageProviderStatus.RETIRED) {
            throw new IllegalStateException("Retired processing storage provider cannot be reactivated");
        }
        return new ProcessingStorageProvider(id, name, providerType, rootPath,
                ProcessingStorageProviderStatus.AVAILABLE, configurationVersion, createdBy, createdAt, now);
    }

    public ProcessingStorageProvider retire(Instant now) {
        return new ProcessingStorageProvider(id, name, providerType, rootPath,
                ProcessingStorageProviderStatus.RETIRED, configurationVersion, createdBy, createdAt, now);
    }

    private static String requireText(String value, String label, int maxLength) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        String normalized = value.trim();
        if (normalized.length() > maxLength) throw new IllegalArgumentException(label + " is too long");
        return normalized;
    }
}
