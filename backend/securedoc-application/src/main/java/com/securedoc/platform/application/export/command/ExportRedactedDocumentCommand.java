package com.securedoc.platform.application.export.command;

import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Objects;

/**
 * Command to export a redacted document to the configured delivery destination.
 */
public record ExportRedactedDocumentCommand(
        DocumentId documentId,
        PackageId packageId,
        TenantId tenantId,
        CorrelationId correlationId
) {
    public ExportRedactedDocumentCommand {
        Objects.requireNonNull(documentId, "DocumentId must not be null");
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
    }

    public static ExportRedactedDocumentCommand of(DocumentId documentId, PackageId packageId,
                                                    TenantId tenantId, CorrelationId correlationId) {
        return new ExportRedactedDocumentCommand(documentId, packageId, tenantId, correlationId);
    }
}
