package com.securedoc.platform.application.integration;

import com.securedoc.platform.application.exception.AccessDeniedException;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.port.out.SdvConnectionTestPort;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.integration.IntegrationConnection;
import com.securedoc.platform.domain.integration.IntegrationPurpose;

import java.util.Objects;

/** Application boundary for an IT-admin safe SDV connectivity/authentication probe. */
public final class SdvConnectionTestService {
    private final IntegrationConnectionService connectionService;
    private final SdvConnectionTestPort testPort;
    private final AuditService auditService;

    public SdvConnectionTestService(IntegrationConnectionService connectionService,
                                    SdvConnectionTestPort testPort,
                                    AuditService auditService) {
        this.connectionService = Objects.requireNonNull(connectionService);
        this.testPort = Objects.requireNonNull(testPort);
        this.auditService = Objects.requireNonNull(auditService);
    }

    public IntegrationConnection test(TenantId tenantId, IntegrationConnectionId connectionId,
                                      ActorId actorId, CorrelationId correlationId) {
        IntegrationConnection connection = connectionService.get(tenantId, connectionId);
        if (!connection.enabled() || connection.purpose() != IntegrationPurpose.SDV_DELIVERY) {
            throw new AccessDeniedException("Integration connection is not an enabled SDV delivery destination");
        }
        try {
            testPort.testConnection(connection);
            IntegrationConnection updated = connectionService.recordOperationalSuccess(connection);
            auditService.recordSuccess(tenantId, actorId, AuditAction.INTEGRATION_CONNECTION_TESTED,
                    "IntegrationConnection", connection.id().toString(), correlationId,
                    "SDV connection test succeeded without customer document transfer");
            return updated;
        } catch (RuntimeException e) {
            String category = e instanceof TechnicalDependencyException tde
                    ? tde.failureCode().name() : "SDV_CONNECTION_TEST_FAILED";
            connectionService.recordOperationalFailure(connection, category);
            auditService.recordFailure(tenantId, actorId, AuditAction.INTEGRATION_CONNECTION_TESTED,
                    "IntegrationConnection", connection.id().toString(), correlationId,
                    "SDV connection test failed: category=" + category);
            throw e;
        }
    }
}
