package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.manual.result.ManualFallbackPagePreviewResult;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

public interface GetManualFallbackPagePreviewQuery {
    ManualFallbackPagePreviewResult execute(PackageId packageId, DocumentId documentId, int pageNumber,
                                            TenantId tenantId, ActorId actorId, CorrelationId correlationId);
}
