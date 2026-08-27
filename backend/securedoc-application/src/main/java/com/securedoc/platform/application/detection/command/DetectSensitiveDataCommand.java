package com.securedoc.platform.application.detection.command;

import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Objects;

/**
 * Command to run sensitive-data detection on a document's extracted text.
 * The document must be in DETECTING status.
 */
public record DetectSensitiveDataCommand(
        DocumentId documentId,
        PackageId packageId,
        TenantId tenantId,
        CorrelationId correlationId
) {

    public DetectSensitiveDataCommand {
        Objects.requireNonNull(documentId, "DocumentId must not be null");
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
    }

    public static DetectSensitiveDataCommand of(DocumentId documentId, PackageId packageId,
                                                 TenantId tenantId, CorrelationId correlationId) {
        return new DetectSensitiveDataCommand(documentId, packageId, tenantId, correlationId);
    }
}
