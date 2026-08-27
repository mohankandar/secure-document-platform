package com.securedoc.platform.application.extraction.command;

import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Objects;

/**
 * Command to extract text from a document's original PDF artifact.
 * Carries the lock key/token from the earlier StartDocumentProcessing step
 * so the lock can be released on completion or failure.
 */
public record ExtractDocumentTextCommand(
        DocumentId documentId,
        PackageId packageId,
        TenantId tenantId,
        String lockKey,
        String lockToken,
        CorrelationId correlationId
) {

    public ExtractDocumentTextCommand {
        Objects.requireNonNull(documentId, "DocumentId must not be null");
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(lockKey, "LockKey must not be null");
        Objects.requireNonNull(lockToken, "LockToken must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
    }

    public static ExtractDocumentTextCommand of(DocumentId documentId, PackageId packageId,
                                                 TenantId tenantId, String lockKey,
                                                 String lockToken, CorrelationId correlationId) {
        return new ExtractDocumentTextCommand(documentId, packageId, tenantId,
                lockKey, lockToken, correlationId);
    }
}
