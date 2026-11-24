package com.securedoc.platform.application.licensing;

import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

/**
 * Task 0057: the meter point. A document's pages are counted once, when its redacted copy is made: not again when the
 * step is retried, when another server takes it over, or when the case is processed again later. Called inside the
 * transaction that saves the document, where there is one, so a result that is not saved is not counted either.
 */
@FunctionalInterface
public interface UsageMeter {

    UsageMeter NONE = (tenantId, packageId, documentId, pages) -> { };

    void documentProcessed(TenantId tenantId, PackageId packageId, DocumentId documentId, int pages);
}
