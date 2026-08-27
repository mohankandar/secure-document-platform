package com.securedoc.platform.application.processing.command;

import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Objects;

/**
 * Command to mark a document's processing as failed.
 * Records the failure reason for audit/diagnostics.
 * Carries lock token so the held processing lock can be released.
 */
public record FailDocumentProcessingCommand(
        DocumentId documentId,
        PackageId packageId,
        TenantId tenantId,
        String failureReason,
        String lockToken,
        CorrelationId correlationId
) {

    public FailDocumentProcessingCommand {
        Objects.requireNonNull(documentId, "DocumentId must not be null");
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
        if (failureReason == null || failureReason.isBlank()) {
            failureReason = "Unknown failure";
        }
        // lockToken may be null if failure occurred before lock was acquired
    }

    public static FailDocumentProcessingCommand of(DocumentId documentId, PackageId packageId,
                                                    TenantId tenantId, String failureReason,
                                                    String lockToken, CorrelationId correlationId) {
        return new FailDocumentProcessingCommand(documentId, packageId, tenantId,
                failureReason, lockToken, correlationId);
    }

    /**
     * Convenience factory when no lock was acquired (failure before lock).
     */
    public static FailDocumentProcessingCommand withoutLock(DocumentId documentId, PackageId packageId,
                                                             TenantId tenantId, String failureReason,
                                                             CorrelationId correlationId) {
        return new FailDocumentProcessingCommand(documentId, packageId, tenantId,
                failureReason, null, correlationId);
    }

    public boolean hasLockToken() {
        return lockToken != null && !lockToken.isBlank();
    }
}
