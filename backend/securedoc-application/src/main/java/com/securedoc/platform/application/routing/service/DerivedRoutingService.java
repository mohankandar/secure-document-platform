package com.securedoc.platform.application.routing.service;

import com.securedoc.platform.application.port.in.ProcessDerivedRoutingUseCase;
import com.securedoc.platform.application.port.out.*;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.artifact.*;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.routing.*;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.MimeType;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Builds and attempts independent derived routing outputs after master delivery succeeds. */
public final class DerivedRoutingService implements ProcessDerivedRoutingUseCase {
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
        List<RoutingRule> active=rules.findEnabledByTenantId(tenantId); if(active.isEmpty()) return;
        for(Document document:documents.findByPackageIdAndTenantId(packageId,tenantId)) processDocument(packageId,tenantId,exportCycle,document,active,correlationId);
    }
    private void processDocument(PackageId packageId,TenantId tenantId,int exportCycle,Document document,List<RoutingRule> active,CorrelationId correlationId){
        Artifact master=artifacts.findByDocumentIdAndTenantId(document.id(),tenantId).stream().filter(a->a.artifactType()==ArtifactType.REDACTED&&a.isAvailable()).max(Comparator.comparing(Artifact::createdAt)).orElse(null);
        if(master==null) return;
        RoutingMarkerDetectionResult result;
        try(var in=storage.retrieve(master.storageReference())){ result=detector.detect(in,active); }
        catch(Exception e){ ensureAttention(tenantId,packageId,document.id(),exportCycle,List.of(),"ROUTING_DETECTION_FAILED",correlationId); return; }
        if(!result.ambiguousPages().isEmpty()) ensureAttention(tenantId,packageId,document.id(),exportCycle,result.ambiguousPages().stream().sorted().toList(),"ROUTING_MARKER_AMBIGUOUS",correlationId);
        for(RoutingRule rule:active){
            List<Integer> pages=result.pagesByRule().getOrDefault(rule.id(),List.of()).stream().filter(p->!result.ambiguousPages().contains(p)).distinct().sorted().toList();
            if(pages.isEmpty()) continue;
            if(deliveries.findByTenantIdAndPackageIdAndExportCycleAndSourceDocumentIdAndRoutingRuleId(tenantId,packageId,exportCycle,document.id(),rule.id()).isPresent()) continue;
            var c=connections.findByTenantIdAndId(tenantId,rule.destinationConnectionId()).orElse(null);
            if(c==null||!c.enabled()){ensureAttention(tenantId,packageId,document.id(),exportCycle,pages,"ROUTING_DESTINATION_UNAVAILABLE",correlationId);continue;}
            try{
                byte[] pdf; try(var in=storage.retrieve(master.storageReference())){pdf=pdfBuilder.build(in,pages);} String sha=sha256(pdf);
                String fileName=derivedFileName(document.fileName().value(),rule.destinationLabel());
                Artifact artifact=Artifact.create(tenantId,document.id(),ArtifactType.DERIVED_REDACTED,MimeType.pdf(),pdf.length,Checksum.sha256(sha));
                ArtifactStorageReference ref=storage.store(tenantId,"derived/"+packageId+"/cycle-"+exportCycle+"/"+document.id()+"/"+artifact.id()+".pdf",new ByteArrayInputStream(pdf),pdf.length,"application/pdf");
                artifact.markAvailable(ref); artifacts.save(artifact);
                DerivedDelivery d=DerivedDelivery.create(tenantId,packageId,document.id(),exportCycle,rule,c.configurationVersion(),pages,artifact.id(),fileName,Instant.now()); deliveries.save(d);
                audit.recordSuccess(tenantId,SYSTEM_ACTOR,AuditAction.DERIVED_DOCUMENT_CREATED,"DerivedDelivery",d.id().toString(),correlationId,
                        "Derived redacted document created for cycle="+exportCycle+", destination="+rule.destinationLabel()+", pages="+pages);
                try{deliveryService.autoSend(tenantId,d.id(),correlationId);}catch(Exception ignored){/* independent delivery state already records failure */}
            }catch(Exception e){ensureAttention(tenantId,packageId,document.id(),exportCycle,pages,"DERIVED_DOCUMENT_BUILD_FAILED",correlationId);}
        }
    }
    private void ensureAttention(TenantId tenantId,PackageId packageId,DocumentId documentId,int exportCycle,List<Integer> pages,String category,CorrelationId correlationId){
        boolean exists=deliveries.findByTenantIdAndPackageId(tenantId,packageId).stream().anyMatch(d->d.sourceDocumentId().equals(documentId)&&d.exportCycle()==exportCycle&&d.routingRuleId()==null&&Objects.equals(d.lastFailureCategory(),category)&&d.pageNumbers().equals(pages));
        if(exists)return; DerivedDelivery attention=DerivedDelivery.routingAttention(tenantId,packageId,documentId,exportCycle,pages,category,Instant.now()); deliveries.save(attention);
        audit.recordFailure(tenantId,SYSTEM_ACTOR,AuditAction.ROUTING_ATTENTION_REQUIRED,"DerivedDelivery",attention.id().toString(),correlationId,"Derived routing requires attention: cycle="+exportCycle+", category="+category+", pages="+pages);
    }
    private String sha256(byte[] bytes){try{byte[] d=MessageDigest.getInstance("SHA-256").digest(bytes);return java.util.HexFormat.of().formatHex(d);}catch(Exception e){throw new IllegalStateException(e);}}
    private String derivedFileName(String original,String destination){String base=original==null?"document":original.replaceAll("(?i)\\.pdf$","");String safe=destination.replaceAll("[^A-Za-z0-9._-]+","_");return base+"__"+safe+".pdf";}
}
