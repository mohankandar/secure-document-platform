package com.securedoc.platform.application.routing.service;

import com.securedoc.platform.domain.error.LvErrorCode;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

/** Independent derived-delivery lifecycle. It never changes master package export/retention state. */
public final class DerivedDeliveryService {
    private static final Logger log = LoggerFactory.getLogger(DerivedDeliveryService.class);
    /** Task 0023 (C4, #192/#185/#186): refusals before an attempt starts, recorded on the delivery row and in the audit. */
    public static final String DESTINATION_NOT_FOUND = "DERIVED_DESTINATION_NOT_FOUND";
    public static final String DESTINATION_WRONG_PURPOSE = "DERIVED_DESTINATION_WRONG_PURPOSE";
    public static final String DESTINATION_DISABLED = "DERIVED_DESTINATION_DISABLED";
    public static final String ARTIFACT_UNAVAILABLE = "DERIVED_ARTIFACT_UNAVAILABLE";
    public static final String SOURCE_NOT_FOUND = "DERIVED_SOURCE_NOT_FOUND";
    private final DerivedDeliveryRepository deliveries;
    private final IntegrationConnectionRepository connections;
    private final ArtifactRepository artifacts;
    private final StoragePort storage;
    private final DerivedDestinationDeliveryPort destination;
    private final AuditService audit;
    private final PackageRepository packages;
    public DerivedDeliveryService(DerivedDeliveryRepository deliveries, IntegrationConnectionRepository connections,
                                  ArtifactRepository artifacts, StoragePort storage,
                                  DerivedDestinationDeliveryPort destination, AuditService audit,
                                  PackageRepository packages){
        this.deliveries=Objects.requireNonNull(deliveries); this.connections=Objects.requireNonNull(connections);
        this.artifacts=Objects.requireNonNull(artifacts); this.storage=Objects.requireNonNull(storage);
        this.destination=Objects.requireNonNull(destination); this.audit=Objects.requireNonNull(audit);
        this.packages=Objects.requireNonNull(packages);
    }
    public List<DerivedDelivery> list(TenantId tenantId, PackageId packageId){return deliveries.findByTenantIdAndPackageId(tenantId,packageId);}
    public List<DerivedDelivery> listAttention(TenantId tenantId){return deliveries.findAttentionByTenantId(tenantId);}
    public DerivedDelivery get(TenantId tenantId, UUID id){return deliveries.findByTenantIdAndId(tenantId,id).orElseThrow(()->new NotFoundException("DerivedDelivery",id.toString()));}
    public DerivedDelivery send(TenantId tenantId, UUID id, ActorId actorId, CorrelationId correlationId){return attempt(tenantId,id,actorId,correlationId,false);}
    public DerivedDelivery technicalRetry(TenantId tenantId, UUID id, ActorId actorId, CorrelationId correlationId){return attempt(tenantId,id,actorId,correlationId,true);}
    public DerivedDelivery autoSend(TenantId tenantId, UUID id, CorrelationId correlationId){return attempt(tenantId,id,ActorId.of(UUID.fromString("00000000-0000-0000-0000-000000000000")),correlationId,true);}

    private DerivedDelivery attempt(TenantId tenantId, UUID id, ActorId actorId, CorrelationId correlationId, boolean technical){
        DerivedDelivery d=get(tenantId,id);
        if(d.artifactId()==null || d.destinationConnectionId()==null) throw new ValidationException(LvErrorCode.DLV_006, "Routing-attention item has no approved destination to send");
        if(!technical && d.status()==DerivedDeliveryStatus.ATTENTION_REQUIRED) throw new ValidationException(LvErrorCode.DLV_014, "Technical attention is required before this derived document can be sent");
        var c=connections.findByTenantIdAndId(tenantId,d.destinationConnectionId()).orElse(null);
        if(c==null) throw refuse(d,DESTINATION_NOT_FOUND,tenantId,actorId,correlationId,new ValidationException(LvErrorCode.DLV_006, "Configured derived destination no longer exists"));
        if(c.purpose()!= IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY || c.direction()!= IntegrationDirection.OUTBOUND) throw refuse(d,DESTINATION_WRONG_PURPOSE,tenantId,actorId,correlationId,new ValidationException(LvErrorCode.DLV_006, "Configured connection is not a derived-document destination"));
        if(!c.enabled()) throw refuse(d,DESTINATION_DISABLED,tenantId,actorId,correlationId,new ValidationException(LvErrorCode.DLV_006, "Derived-document destination is disabled"));
        Artifact artifact=artifacts.findByIdAndTenantId(d.artifactId(),tenantId).orElse(null);
        if(artifact==null) throw refuse(d,ARTIFACT_UNAVAILABLE,tenantId,actorId,correlationId,new NotFoundException("Derived artifact",d.artifactId().toString()));
        if(!artifact.isAvailable()) throw refuse(d,ARTIFACT_UNAVAILABLE,tenantId,actorId,correlationId,new ValidationException(LvErrorCode.DLV_011, "Derived redacted artifact is not available"));
        // Task 0021: a split document carries its source document's level and the case level, as stored at intake.
        var pkg=packages.findByIdAndTenantId(d.packageId(),tenantId).orElse(null);
        if(pkg==null) throw refuse(d,SOURCE_NOT_FOUND,tenantId,actorId,correlationId,new NotFoundException("DocumentPackage",d.packageId().toString()));
        var sourceDocument=pkg.documents().stream().filter(doc->doc.id().equals(d.sourceDocumentId())).findFirst().orElse(null);
        if(sourceDocument==null) throw refuse(d,SOURCE_NOT_FOUND,tenantId,actorId,correlationId,new NotFoundException("Source document",d.sourceDocumentId().toString()));
        String documentLevel=sourceDocument.sensitivityLevel().name(), caseLevel=pkg.sensitivityLevel().name();
        d.startAttempt(Instant.now()); deliveries.save(d);
        DerivedDeliveryAttempt attempt=DerivedDeliveryAttempt.started(d.id(),d.attemptCount(),c.id(),c.configurationVersion(),Instant.now()); deliveries.saveAttempt(attempt);
        try{
            var receipt=destination.deliver(c,new DerivedDestinationDeliveryPort.DeliveryRequest(
                    d.sourceDerivedDocumentId(),d.fileName(),"application/pdf",artifact.sizeBytes(),artifact.checksum().value(),d.markerValue(),
                    Map.of("packageId",d.packageId().toString(),"sourceDocumentId",d.sourceDocumentId().toString(),"destinationLabel",d.destinationLabel(),"pages",joinPages(d.pageNumbers()),
                            DerivedDestinationDeliveryPort.SENSITIVITY_LEVEL,documentLevel,DerivedDestinationDeliveryPort.CASE_SENSITIVITY_LEVEL,caseLevel),
                    ()->storage.retrieve(artifact.storageReference())));
            d.markDelivered(receipt.destinationReference(),Instant.now()); deliveries.save(d); deliveries.saveAttempt(attempt.success(Instant.now()));
            connections.save(c.recordOperationalSuccess(Instant.now()));
            audit.recordSuccess(tenantId,actorId,AuditAction.DERIVED_DOCUMENT_DELIVERED,"DerivedDelivery",d.id().toString(),correlationId,
                    "Derived redacted document delivered to approved destination="+d.destinationLabel()+", pages="+joinPages(d.pageNumbers())
                            +", sensitivityLevel="+documentLevel+", caseSensitivityLevel="+caseLevel);
            return d;
        }catch(TechnicalDependencyException e){
            d.markFailure(e.failureCode().name(),e.retryable(),Instant.now()); deliveries.save(d); deliveries.saveAttempt(attempt.failure(e.failureCode().name(),Instant.now()));
            connections.save(c.recordOperationalFailure(e.failureCode().name(),Instant.now()));
            audit.recordFailure(tenantId,actorId,AuditAction.DERIVED_DOCUMENT_DELIVERY_FAILED,"DerivedDelivery",d.id().toString(),correlationId,
                    "Derived delivery failed: "+com.securedoc.platform.application.error.LvErrorCodes.auditDetail(com.securedoc.platform.application.error.LvErrorCodes.of(e),e.failureCode().name(),"retryable="+e.retryable())); throw e;
        }catch(RuntimeException e){
            d.markFailure("DERIVED_DELIVERY_RUNTIME",true,Instant.now()); deliveries.save(d); deliveries.saveAttempt(attempt.failure("DERIVED_DELIVERY_RUNTIME",Instant.now()));
            connections.save(c.recordOperationalFailure("DERIVED_DELIVERY_RUNTIME",Instant.now()));
            audit.recordFailure(tenantId,actorId,AuditAction.DERIVED_DOCUMENT_DELIVERY_FAILED,"DerivedDelivery",d.id().toString(),correlationId,"Derived delivery failed: "+com.securedoc.platform.application.error.LvErrorCodes.auditDetail(com.securedoc.platform.domain.error.LvErrorCode.SYS_001,"DERIVED_DELIVERY_RUNTIME","exceptionType="+e.getClass().getSimpleName())); throw e;
        }
    }
    /**
     * Task 0023 (C4): a refusal before the attempt starts used to leave an automatic send PENDING with no record.
     * It now marks the delivery ATTENTION_REQUIRED with the code (so it is in the attention list), audits the code
     * and logs it. No retry is scheduled. The caller gets the same exception as before.
     */
    private RuntimeException refuse(DerivedDelivery d,String code,TenantId tenantId,ActorId actorId,CorrelationId correlationId,RuntimeException toThrow){
        d.markFailure(code,false,Instant.now()); deliveries.save(d);
        audit.recordFailure(tenantId,actorId,AuditAction.DERIVED_DOCUMENT_DELIVERY_FAILED,"DerivedDelivery",d.id().toString(),correlationId,
                "Derived delivery refused before sending: "+com.securedoc.platform.application.error.LvErrorCodes.auditDetail(com.securedoc.platform.application.error.LvErrorCodes.forDetailOrFallback(code,"derived"),code,"retryable=false"));
        log.warn("DERIVED_DELIVERY_REFUSED derivedDelivery={} package={} code={}",d.id(),d.packageId(),code);
        return toThrow;
    }
    private String joinPages(List<Integer> pages){return pages.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));}
}
