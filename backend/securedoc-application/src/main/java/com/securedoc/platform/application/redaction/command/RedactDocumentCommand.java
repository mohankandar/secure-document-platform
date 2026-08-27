package com.securedoc.platform.application.redaction.command;

import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Objects;

/**
 * Command to redact a document based on its detected findings.
 * The document must be in REDACTING status.
 */
public record RedactDocumentCommand(
        DocumentId documentId,
        PackageId packageId,
        TenantId tenantId,
        CorrelationId correlationId
) {

    public RedactDocumentCommand {
        Objects.requireNonNull(documentId, "DocumentId must not be null");
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
    }

    public static RedactDocumentCommand of(DocumentId documentId, PackageId packageId,
                                            TenantId tenantId, CorrelationId correlationId) {
        return new RedactDocumentCommand(documentId, packageId, tenantId, correlationId);
    }
}
