package com.securedoc.platform.application.port.in;

import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.application.vault.result.ArtifactAccessRequestResult;

import java.util.List;

/**
 * Inbound port for listing access requests for a tenant.
 */
public interface ListArtifactAccessRequestsQuery {
    List<ArtifactAccessRequestResult> execute(TenantId tenantId);
}
