package com.securedoc.platform.application.vault.command;

import com.securedoc.platform.domain.identity.AccessRequestId;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Objects;

/**
 * Command to approve or deny an access request for a restricted artifact.
 */
public record DecideArtifactAccessRequestCommand(
        AccessRequestId requestId,
        TenantId tenantId,
        ActorId deciderId,
        boolean approved,
        String reason,
        CorrelationId correlationId
) {
    public DecideArtifactAccessRequestCommand {
        Objects.requireNonNull(requestId, "RequestId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(deciderId, "DeciderId must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
    }

    public static DecideArtifactAccessRequestCommand of(AccessRequestId requestId, TenantId tenantId,
                                                         ActorId deciderId, boolean approved,
                                                         String reason, CorrelationId correlationId) {
        return new DecideArtifactAccessRequestCommand(requestId, tenantId, deciderId,
                approved, reason, correlationId);
    }
}
