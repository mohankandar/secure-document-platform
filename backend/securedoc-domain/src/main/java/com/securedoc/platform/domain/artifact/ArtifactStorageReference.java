package com.securedoc.platform.domain.artifact;

import java.util.Objects;

/**
 * Reference to where an artifact is stored. Provider-neutral pointer
 * that the infrastructure layer resolves to actual storage access.
 */
public record ArtifactStorageReference(String storageProvider, String bucket, String key) {

    public ArtifactStorageReference {
        Objects.requireNonNull(storageProvider, "Storage provider must not be null");
        Objects.requireNonNull(bucket, "Bucket must not be null");
        Objects.requireNonNull(key, "Key must not be null");
        if (storageProvider.isBlank()) {
            throw new IllegalArgumentException("Storage provider must not be blank");
        }
        if (key.isBlank()) {
            throw new IllegalArgumentException("Storage key must not be blank");
        }
    }

    public static ArtifactStorageReference of(String provider, String bucket, String key) {
        return new ArtifactStorageReference(provider, bucket, key);
    }

    public static ArtifactStorageReference local(String path) {
        return new ArtifactStorageReference("local", "filesystem", path);
    }

    @Override
    public String toString() {
        return storageProvider + "://" + bucket + "/" + key;
    }
}
