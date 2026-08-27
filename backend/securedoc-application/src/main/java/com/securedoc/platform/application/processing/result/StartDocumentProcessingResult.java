package com.securedoc.platform.application.processing.result;

import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Objects;

/**
 * Result returned after starting document processing (extraction).
 * Includes lock information so callers can release the lock on completion or failure.
 */
public record StartDocumentProcessingResult(
        DocumentId documentId,
        PackageId packageId,
        TenantId tenantId,
        DocumentStatus status,
        String lockKey,
        String lockToken
) {

    public StartDocumentProcessingResult {
        Objects.requireNonNull(documentId, "DocumentId must not be null");
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(status, "Status must not be null");
        Objects.requireNonNull(lockKey, "LockKey must not be null");
        Objects.requireNonNull(lockToken, "LockToken must not be null");
    }

    public static StartDocumentProcessingResult of(DocumentId documentId, PackageId packageId,
                                                    TenantId tenantId, DocumentStatus status,
                                                    String lockKey, String lockToken) {
        return new StartDocumentProcessingResult(documentId, packageId, tenantId, status, lockKey, lockToken);
    }
}
