package com.securedoc.platform.application.port.in;

import com.securedoc.platform.domain.identity.AccessRequestId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.application.vault.result.ArtifactAccessRequestResult;

/**
 * Inbound port for fetching a single access request by ID.
 */
public interface GetArtifactAccessRequestQuery {
    ArtifactAccessRequestResult execute(AccessRequestId requestId, TenantId tenantId);
}
