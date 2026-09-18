package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.artifact.ArtifactStorageReference;
import com.securedoc.platform.domain.identity.PackageId;
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
     * Stores content on the provider pinned to the package's current processing cycle.
     */
    default ArtifactStorageReference storeForPackage(TenantId tenantId, PackageId packageId,
                                                       String path, InputStream content,
                                                       long sizeBytes, String mimeType) {
        return store(tenantId, path, content, sizeBytes, mimeType);
    }

    /**
     * Stores content on the provider pinned to one package processing cycle. Implementations must
     * keep the pin stable even if Platform Operations activates a different provider concurrently.
     */
    default ArtifactStorageReference storeForProcessingCycle(TenantId tenantId, PackageId packageId, int processingCycle,
                                                               String path, InputStream content,
                                                               long sizeBytes, String mimeType) {
        if (processingCycle < 1) throw new IllegalArgumentException("Processing cycle must be >= 1");
        return store(tenantId, path, content, sizeBytes, mimeType);
    }

    /**
     * Retrieves stored content by reference.
     */
    InputStream retrieve(ArtifactStorageReference reference);

    /**
     * Deletes stored content by reference.
     */
    void delete(ArtifactStorageReference reference);
}
