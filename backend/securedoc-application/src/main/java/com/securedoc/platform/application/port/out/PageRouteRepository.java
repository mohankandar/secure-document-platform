package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.vision.PageRoute;

import java.util.List;

/** Task 0064: how each page of a document is read, and why. No value and no text. */
public interface PageRouteRepository {

    /** Writes these pages' routes; a page that has one gets the new one. */
    void save(TenantId tenantId, DocumentId documentId, List<PageRoute> routes);

    /** In page order. */
    List<PageRoute> findByDocument(TenantId tenantId, DocumentId documentId);
}
