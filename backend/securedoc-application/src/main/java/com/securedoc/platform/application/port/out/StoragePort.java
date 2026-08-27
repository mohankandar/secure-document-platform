package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.artifact.ArtifactStorageReference;
import com.securedoc.platform.domain.identity.TenantId;

import java.io.InputStream;

/**
 * Outbound port for storing and retrieving file content.
 * Implementation is infrastructure-specific (local filesystem, S3, etc.).
 */
public interface StoragePort {

    /**
     * Stores content and returns a reference to the stored location.
     *
     * @param tenantId   owning tenant
     * @param path       logical path/key for the file
     * @param content    file content stream
     * @param sizeBytes  expected content length
     * @param mimeType   content MIME type
     * @return storage reference for later retrieval
     */
    ArtifactStorageReference store(TenantId tenantId, String path, InputStream content,
                                    long sizeBytes, String mimeType);

    /**
     * Retrieves stored content by reference.
     */
    InputStream retrieve(ArtifactStorageReference reference);

    /**
     * Deletes stored content by reference.
     */
    void delete(ArtifactStorageReference reference);
}
