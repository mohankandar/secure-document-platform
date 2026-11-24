package com.securedoc.platform.application.pipeline;

import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Objects;

/** Task 0049: read or search one page batch of a document. */
public record PageBatchCommand(PageBatch batch, DocumentId documentId, PackageId packageId, TenantId tenantId,
                               CorrelationId correlationId) {
    public PageBatchCommand {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(packageId, "packageId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(correlationId, "correlationId");
    }
}
