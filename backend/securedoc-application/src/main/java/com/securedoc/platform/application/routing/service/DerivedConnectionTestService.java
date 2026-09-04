package com.securedoc.platform.application.routing.service;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.DerivedDestinationDeliveryPort;
import com.securedoc.platform.application.port.out.IntegrationConnectionRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.integration.*;

import java.time.Instant;
import java.util.Objects;

/** Safe no-customer-document health check for derived destinations. */
public final class DerivedConnectionTestService {
    private final IntegrationConnectionRepository connections; private final DerivedDestinationDeliveryPort port; private final AuditService audit;
    public DerivedConnectionTestService(IntegrationConnectionRepository connections,DerivedDestinationDeliveryPort port,AuditService audit){this.connections=Objects.requireNonNull(connections);this.port=Objects.requireNonNull(port);this.audit=Objects.requireNonNull(audit);}
    public IntegrationConnection test(TenantId tenantId, IntegrationConnectionId id, ActorId actorId, CorrelationId correlationId){
        IntegrationConnection c=connections.findByTenantIdAndId(tenantId,id).orElseThrow(()->new NotFoundException("IntegrationConnection",id.toString()));
        if(c.purpose()!= IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY) throw new ValidationException("Connection is not a derived-document destination");
        try{port.testConnection(c);IntegrationConnection updated=c.recordOperationalSuccess(Instant.now());connections.save(updated);audit.recordSuccess(tenantId,actorId,AuditAction.INTEGRATION_CONNECTION_TESTED,"IntegrationConnection",id.toString(),correlationId,"Derived-document destination connection test succeeded");return updated;}
        catch(RuntimeException e){IntegrationConnection updated=c.recordOperationalFailure("DERIVED_CONNECTION_TEST_FAILED",Instant.now());connections.save(updated);audit.recordFailure(tenantId,actorId,AuditAction.INTEGRATION_CONNECTION_TESTED,"IntegrationConnection",id.toString(),correlationId,"Derived-document destination connection test failed");throw e;}
    }
}
