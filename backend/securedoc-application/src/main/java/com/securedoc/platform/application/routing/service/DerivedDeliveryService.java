package com.securedoc.platform.application.routing.service;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.*;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.integration.IntegrationDirection;
import com.securedoc.platform.domain.integration.IntegrationPurpose;
import com.securedoc.platform.domain.routing.*;

import java.time.Instant;
import java.util.*;

/** Independent derived-delivery lifecycle. It never changes master package export/retention state. */
public final class DerivedDeliveryService {
    private final DerivedDeliveryRepository deliveries;
    private final IntegrationConnectionRepository connections;
    private final ArtifactRepository artifacts;
    private final StoragePort storage;
    private final DerivedDestinationDeliveryPort destination;
    private final AuditService audit;
    public DerivedDeliveryService(DerivedDeliveryRepository deliveries, IntegrationConnectionRepository connections,
                                  ArtifactRepository artifacts, StoragePort storage,
                                  DerivedDestinationDeliveryPort destination, AuditService audit){
        this.deliveries=Objects.requireNonNull(deliveries); this.connections=Objects.requireNonNull(connections);
        this.artifacts=Objects.requireNonNull(artifacts); this.storage=Objects.requireNonNull(storage);
        this.destination=Objects.requireNonNull(destination); this.audit=Objects.requireNonNull(audit);
    }
    public List<DerivedDelivery> list(TenantId tenantId, PackageId packageId){return deliveries.findByTenantIdAndPackageId(tenantId,packageId);}
    public List<DerivedDelivery> listAttention(TenantId tenantId){return deliveries.findAttentionByTenantId(tenantId);}
    public DerivedDelivery get(TenantId tenantId, UUID id){return deliveries.findByTenantIdAndId(tenantId,id).orElseThrow(()->new NotFoundException("DerivedDelivery",id.toString()));}
    public DerivedDelivery send(TenantId tenantId, UUID id, ActorId actorId, CorrelationId correlationId){return attempt(tenantId,id,actorId,correlationId,false);}
    public DerivedDelivery technicalRetry(TenantId tenantId, UUID id, ActorId actorId, CorrelationId correlationId){return attempt(tenantId,id,actorId,correlationId,true);}
    public DerivedDelivery autoSend(TenantId tenantId, UUID id, CorrelationId correlationId){return attempt(tenantId,id,ActorId.of(UUID.fromString("00000000-0000-0000-0000-000000000000")),correlationId,true);}

    private DerivedDelivery attempt(TenantId tenantId, UUID id, ActorId actorId, CorrelationId correlationId, boolean technical){
        DerivedDelivery d=get(tenantId,id);
        if(d.artifactId()==null || d.destinationConnectionId()==null) throw new ValidationException("Routing-attention item has no approved destination to send");
        if(!technical && d.status()==DerivedDeliveryStatus.ATTENTION_REQUIRED) throw new ValidationException("Technical attention is required before this derived document can be sent");
        var c=connections.findByTenantIdAndId(tenantId,d.destinationConnectionId()).orElseThrow(()->new ValidationException("Configured derived destination no longer exists"));
        if(c.purpose()!= IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY || c.direction()!= IntegrationDirection.OUTBOUND) throw new ValidationException("Configured connection is not a derived-document destination");
        if(!c.enabled()){
            d.markFailure("DERIVED_DESTINATION_DISABLED",false,Instant.now()); deliveries.save(d);
            throw new ValidationException("Derived-document destination is disabled");
        }
        Artifact artifact=artifacts.findByIdAndTenantId(d.artifactId(),tenantId).orElseThrow(()->new NotFoundException("Derived artifact",d.artifactId().toString()));
        if(!artifact.isAvailable()) throw new ValidationException("Derived redacted artifact is not available");
        d.startAttempt(Instant.now()); deliveries.save(d);
        DerivedDeliveryAttempt attempt=DerivedDeliveryAttempt.started(d.id(),d.attemptCount(),c.id(),c.configurationVersion(),Instant.now()); deliveries.saveAttempt(attempt);
        try{
            var receipt=destination.deliver(c,new DerivedDestinationDeliveryPort.DeliveryRequest(
                    d.sourceDerivedDocumentId(),d.fileName(),"application/pdf",artifact.sizeBytes(),artifact.checksum().value(),d.markerValue(),
                    Map.of("packageId",d.packageId().toString(),"sourceDocumentId",d.sourceDocumentId().toString(),"destinationLabel",d.destinationLabel(),"pages",joinPages(d.pageNumbers())),
                    ()->storage.retrieve(artifact.storageReference())));
            d.markDelivered(receipt.destinationReference(),Instant.now()); deliveries.save(d); deliveries.saveAttempt(attempt.success(Instant.now()));
            connections.save(c.recordOperationalSuccess(Instant.now()));
            audit.recordSuccess(tenantId,actorId,AuditAction.DERIVED_DOCUMENT_DELIVERED,"DerivedDelivery",d.id().toString(),correlationId,
                    "Derived redacted document delivered to approved destination="+d.destinationLabel()+", pages="+joinPages(d.pageNumbers()));
            return d;
        }catch(TechnicalDependencyException e){
            d.markFailure(e.failureCode().name(),e.retryable(),Instant.now()); deliveries.save(d); deliveries.saveAttempt(attempt.failure(e.failureCode().name(),Instant.now()));
            connections.save(c.recordOperationalFailure(e.failureCode().name(),Instant.now()));
            audit.recordFailure(tenantId,actorId,AuditAction.DERIVED_DOCUMENT_DELIVERY_FAILED,"DerivedDelivery",d.id().toString(),correlationId,
                    "Derived delivery failed: code="+e.failureCode()+",retryable="+e.retryable()); throw e;
        }catch(RuntimeException e){
            d.markFailure("DERIVED_DELIVERY_RUNTIME",true,Instant.now()); deliveries.save(d); deliveries.saveAttempt(attempt.failure("DERIVED_DELIVERY_RUNTIME",Instant.now()));
            connections.save(c.recordOperationalFailure("DERIVED_DELIVERY_RUNTIME",Instant.now()));
            audit.recordFailure(tenantId,actorId,AuditAction.DERIVED_DOCUMENT_DELIVERY_FAILED,"DerivedDelivery",d.id().toString(),correlationId,"Derived delivery failed"); throw e;
        }
    }
    private String joinPages(List<Integer> pages){return pages.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));}
}
