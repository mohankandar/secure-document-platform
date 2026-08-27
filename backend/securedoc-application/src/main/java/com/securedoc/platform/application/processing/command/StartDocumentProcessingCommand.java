package com.securedoc.platform.application.processing.command;

import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Objects;

/**
 * Command to start processing (extraction) for a single document.
 * The document must be in UPLOADED status and belong to a PROCESSING package.
 */
public record StartDocumentProcessingCommand(
        DocumentId documentId,
        PackageId packageId,
        TenantId tenantId,
        CorrelationId correlationId
) {

    public StartDocumentProcessingCommand {
        Objects.requireNonNull(documentId, "DocumentId must not be null");
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
    }

    public static StartDocumentProcessingCommand of(DocumentId documentId, PackageId packageId,
                                                     TenantId tenantId, CorrelationId correlationId) {
        return new StartDocumentProcessingCommand(documentId, packageId, tenantId, correlationId);
    }
}
