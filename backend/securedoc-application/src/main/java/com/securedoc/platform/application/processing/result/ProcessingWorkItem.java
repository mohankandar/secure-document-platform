package com.securedoc.platform.application.processing.result;

import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Objects;

/**
 * Represents a document that is ready for processing work.
 * Returned by the processing orchestration to indicate what needs processing.
 */
public record ProcessingWorkItem(
        DocumentId documentId,
        PackageId packageId,
        TenantId tenantId,
        DocumentStatus currentStatus
) {

    public ProcessingWorkItem {
        Objects.requireNonNull(documentId, "DocumentId must not be null");
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(currentStatus, "CurrentStatus must not be null");
    }

    public static ProcessingWorkItem of(DocumentId documentId, PackageId packageId,
                                         TenantId tenantId, DocumentStatus currentStatus) {
        return new ProcessingWorkItem(documentId, packageId, tenantId, currentStatus);
    }
}
