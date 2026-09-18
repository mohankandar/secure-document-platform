package com.securedoc.platform.application.routing.service;

import com.securedoc.platform.application.port.in.ProcessDerivedRoutingUseCase;
import com.securedoc.platform.application.port.out.*;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.application.observability.StructuredLogContext;
import com.securedoc.platform.domain.artifact.*;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.routing.*;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.MimeType;

import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Builds and attempts independent derived routing outputs after master delivery succeeds. */
public final class DerivedRoutingService implements ProcessDerivedRoutingUseCase {
    private static final Logger log = LoggerFactory.getLogger(DerivedRoutingService.class);
    private static final ActorId SYSTEM_ACTOR=ActorId.of(UUID.fromString("00000000-0000-0000-0000-000000000000"));
    private final RoutingRuleRepository rules; private final DerivedDeliveryRepository deliveries;
    private final IntegrationConnectionRepository connections; private final DocumentRepository documents;
    private final ArtifactRepository artifacts; private final StoragePort storage; private final RoutingMarkerDetectionPort detector;
    private final DerivedPdfBuilderPort pdfBuilder; private final DerivedDeliveryService deliveryService; private final AuditService audit;
    public DerivedRoutingService(RoutingRuleRepository rules, DerivedDeliveryRepository deliveries, IntegrationConnectionRepository connections,
                                 DocumentRepository documents, ArtifactRepository artifacts, StoragePort storage,
                                 RoutingMarkerDetectionPort detector, DerivedPdfBuilderPort pdfBuilder,
                                 DerivedDeliveryService deliveryService, AuditService audit){
        this.rules=Objects.requireNonNull(rules);this.deliveries=Objects.requireNonNull(deliveries);this.connections=Objects.requireNonNull(connections);
        this.documents=Objects.requireNonNull(documents);this.artifacts=Objects.requireNonNull(artifacts);this.storage=Objects.requireNonNull(storage);
        this.detector=Objects.requireNonNull(detector);this.pdfBuilder=Objects.requireNonNull(pdfBuilder);this.deliveryService=Objects.requireNonNull(deliveryService);this.audit=Objects.requireNonNull(audit);
    }
    @Override public void processPackage(PackageId packageId, TenantId tenantId, int exportCycle, CorrelationId correlationId){
        long startedNanos=System.nanoTime();
        List<RoutingRule> active=rules.findEnabledByTenantId(tenantId);
        if(active.isEmpty()){
            StructuredLogContext.info(log,"DERIVED_ROUTING_COMPLETED",Map.of(
                    "exportCycle",exportCycle,
                    "enabledRules",0,
                    "documents",0,
                    "durationMs",elapsedMillis(startedNanos),
                    "status","SKIPPED"),"Derived routing completed");
            return;
        }
        List<Document> packageDocuments=documents.findByPackageIdAndTenantId(packageId,tenantId);
        try{
            for(Document document:packageDocuments) processDocument(packageId,tenantId,exportCycle,document,active,correlationId);
        }finally{
            StructuredLogContext.info(log,"DERIVED_ROUTING_COMPLETED",Map.of(
                    "exportCycle",exportCycle,
                    "enabledRules",active.size(),
                    "documents",packageDocuments.size(),
                    "durationMs",elapsedMillis(startedNanos),
                    "status","COMPLETED"),"Derived routing completed");
        }
    }
    private void processDocument(PackageId packageId,TenantId tenantId,int exportCycle,Document document,List<RoutingRule> active,CorrelationId correlationId){
        Artifact master=artifacts.findByDocumentIdAndTenantId(document.id(),tenantId).stream().filter(a->a.artifactType()==ArtifactType.REDACTED&&a.isAvailable()).max(Comparator.comparing(Artifact::createdAt)).orElse(null);
        if(master==null) return;
        RoutingMarkerDetectionResult result;
        long detectionStartedNanos=System.nanoTime();
        try(var in=storage.retrieve(master.storageReference())){
            result=detector.detect(in,active);
            int matchedRules=result.pagesByRule().size();
            StructuredLogContext.info(log,"DERIVED_ROUTING_DETECTION_COMPLETED",Map.of(
                    "documentId",document.id(),
                    "exportCycle",exportCycle,
                    "enabledRules",active.size(),
                    "matchedRules",matchedRules,
                    "ambiguousPages",result.ambiguousPages().size(),
                    "durationMs",elapsedMillis(detectionStartedNanos),
                    "status","COMPLETED"),"Derived routing marker detection completed");
        }
        catch(Exception e){
            StructuredLogContext.warn(log,"DERIVED_ROUTING_DETECTION_FAILED",Map.of(
                    "documentId",document.id(),
                    "exportCycle",exportCycle,
                    "enabledRules",active.size(),
                    "durationMs",elapsedMillis(detectionStartedNanos),
                    "reasonCode",e.getClass().getSimpleName(),
                    "status","FAILED"),"Derived routing marker detection failed");
            ensureAttention(tenantId,packageId,document.id(),exportCycle,List.of(),"ROUTING_DETECTION_FAILED",correlationId); return;
        }
        if(!result.ambiguousPages().isEmpty()) ensureAttention(tenantId,packageId,document.id(),exportCycle,result.ambiguousPages().stream().sorted().toList(),"ROUTING_MARKER_AMBIGUOUS",correlationId);
        for(RoutingRule rule:active){
            List<Integer> pages=result.pagesByRule().getOrDefault(rule.id(),List.of()).stream().filter(p->!result.ambiguousPages().contains(p)).distinct().sorted().toList();
            if(pages.isEmpty()) continue;
            if(deliveries.findByTenantIdAndPackageIdAndExportCycleAndSourceDocumentIdAndRoutingRuleId(tenantId,packageId,exportCycle,document.id(),rule.id()).isPresent()) continue;
            var c=connections.findByTenantIdAndId(tenantId,rule.destinationConnectionId()).orElse(null);
            if(c==null||!c.enabled()){ensureAttention(tenantId,packageId,document.id(),exportCycle,pages,"ROUTING_DESTINATION_UNAVAILABLE",correlationId);continue;}
            try{
                ReplayableContent pdf;
                try(var in=storage.retrieve(master.storageReference())){pdf=pdfBuilder.build(in,pages);}
                try(pdf){
                    String sha; try(var in=pdf.openStream()){sha=sha256(in);}
                    long pdfSize=pdf.contentLength();
                    String fileName=derivedFileName(document.fileName().value(),rule.destinationLabel());
                    Artifact artifact=Artifact.create(tenantId,document.id(),ArtifactType.DERIVED_REDACTED,MimeType.pdf(),pdfSize,Checksum.sha256(sha));
                    ArtifactStorageReference ref;
                    try(var in=pdf.openStream()){
                        ref=storage.storeForProcessingCycle(tenantId,packageId,exportCycle,"derived/"+packageId+"/cycle-"+exportCycle+"/"+document.id()+"/"+artifact.id()+".pdf",in,pdfSize,"application/pdf");
                    }
                    artifact.markAvailable(ref); artifacts.save(artifact);
                    DerivedDelivery d=DerivedDelivery.create(tenantId,packageId,document.id(),exportCycle,rule,c.configurationVersion(),pages,artifact.id(),fileName,Instant.now()); deliveries.save(d);
                    audit.recordSuccess(tenantId,SYSTEM_ACTOR,AuditAction.DERIVED_DOCUMENT_CREATED,"DerivedDelivery",d.id().toString(),correlationId,
                            "Derived redacted document created for cycle="+exportCycle+", destination="+rule.destinationLabel()+", pages="+pages);
                    try{deliveryService.autoSend(tenantId,d.id(),correlationId);}catch(Exception ignored){/* independent delivery state already records failure */}
                }
            }catch(Exception e){ensureAttention(tenantId,packageId,document.id(),exportCycle,pages,"DERIVED_DOCUMENT_BUILD_FAILED",correlationId);}
        }
    }
    private void ensureAttention(TenantId tenantId,PackageId packageId,DocumentId documentId,int exportCycle,List<Integer> pages,String category,CorrelationId correlationId){
        boolean exists=deliveries.findByTenantIdAndPackageId(tenantId,packageId).stream().anyMatch(d->d.sourceDocumentId().equals(documentId)&&d.exportCycle()==exportCycle&&d.routingRuleId()==null&&Objects.equals(d.lastFailureCategory(),category)&&d.pageNumbers().equals(pages));
        if(exists)return; DerivedDelivery attention=DerivedDelivery.routingAttention(tenantId,packageId,documentId,exportCycle,pages,category,Instant.now()); deliveries.save(attention);
        audit.recordFailure(tenantId,SYSTEM_ACTOR,AuditAction.ROUTING_ATTENTION_REQUIRED,"DerivedDelivery",attention.id().toString(),correlationId,"Derived routing requires attention: cycle="+exportCycle+", category="+category+", pages="+pages);
    }
    private long elapsedMillis(long startedNanos){return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-startedNanos);}
    private String sha256(InputStream content){
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            byte[] buffer=new byte[8192];
            int read;
            while((read=content.read(buffer))!=-1){digest.update(buffer,0,read);}
            return java.util.HexFormat.of().formatHex(digest.digest());
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    private String derivedFileName(String original,String destination){String base=original==null?"document":original.replaceAll("(?i)\\.pdf$","");String safe=destination.replaceAll("[^A-Za-z0-9._-]+","_");return base+"__"+safe+".pdf";}
}
