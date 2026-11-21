package com.securedoc.platform.api.routing;

import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.routing.service.DerivedDeliveryAttentionQuery;
import com.securedoc.platform.application.routing.service.DerivedDeliveryService;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.routing.DerivedDelivery;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class DerivedDeliveryController {
    private final DerivedDeliveryService service; private final DerivedDeliveryAttentionQuery attentionQuery; private final RequestContextResolver context;
    public DerivedDeliveryController(DerivedDeliveryService service,DerivedDeliveryAttentionQuery attentionQuery,RequestContextResolver context){this.service=service;this.attentionQuery=attentionQuery;this.context=context;}
    @GetMapping("/packages/{packageId}/derived-deliveries") @RequireCapability(UserCapability.PACKAGE_APPROVE_DELIVERY)
    public List<Response> list(@PathVariable String packageId,HttpServletRequest request){var p=context.resolvePrincipal(request);return service.list(p.tenantId(),packageId(packageId)).stream().map(Response::of).toList();}
    @PostMapping("/packages/{packageId}/derived-deliveries/{deliveryId}/send") @RequireCapability(UserCapability.PACKAGE_APPROVE_DELIVERY)
    public ResponseEntity<Response> send(@PathVariable String packageId,@PathVariable String deliveryId,HttpServletRequest request){var p=context.resolvePrincipal(request);DerivedDelivery d=service.get(p.tenantId(),uuid(deliveryId));if(!d.packageId().equals(packageId(packageId)))throw new ValidationException(LvErrorCode.CFG_001, "Derived delivery does not belong to this package");return ResponseEntity.ok(Response.of(service.send(p.tenantId(),d.id(),p.actorId(),context.resolveCorrelationId(request))));}
    @GetMapping("/derived-deliveries/attention") @RequireCapability(UserCapability.TECHNICAL_EXCEPTION_VIEW)
    public List<AttentionResponse> attention(HttpServletRequest request){var p=context.resolvePrincipal(request);return attentionQuery.listAttention(p.tenantId()).stream().map(AttentionResponse::of).toList();}
    @PostMapping("/derived-deliveries/{deliveryId}/technical-retry") @RequireCapability(UserCapability.TECHNICAL_RETRY)
    public ResponseEntity<Response> retry(@PathVariable String deliveryId,HttpServletRequest request){var p=context.resolvePrincipal(request);return ResponseEntity.ok(Response.of(service.technicalRetry(p.tenantId(),uuid(deliveryId),p.actorId(),context.resolveCorrelationId(request))));}
    private UUID uuid(String v){try{return UUID.fromString(v);}catch(Exception e){throw new ValidationException(LvErrorCode.CFG_001, "Invalid derived delivery id");}}private PackageId packageId(String v){try{return PackageId.of(UUID.fromString(v));}catch(Exception e){throw new ValidationException(LvErrorCode.CFG_001, "Invalid package id");}}
    /**
     * Task 0023 (B4, #222): no file name in the response. The split file name is built from the original file name,
     * so it is kept for delivery only; a screen names the item by {@code sourceDocumentId}. A file-share
     * {@code destinationReference} written before task 0023 was the file name itself ("derived-...__name.pdf"):
     * only its id part (before "__") is returned.
     */
    public record Response(String derivedDeliveryId,String packageId,String sourceDocumentId,int exportCycle,String destinationLabel,String markerType,String markerValue,List<Integer> pageNumbers,String status,int attemptCount,String failureCategory,boolean retryable,String destinationReference,String deliveredAt){static Response of(DerivedDelivery d){return new Response(d.id().toString(),d.packageId().toString(),d.sourceDocumentId().toString(),d.exportCycle(),d.destinationLabel(),d.markerType()==null?null:d.markerType().name(),d.markerValue(),d.pageNumbers(),d.status().name(),d.attemptCount(),d.lastFailureCategory(),d.retryable(),safeReference(d.destinationReference()),d.deliveredAt()==null?null:d.deliveredAt().toString());}}
    static String safeReference(String reference){if(reference==null||!reference.startsWith("derived-"))return reference;int cut=reference.indexOf("__");return cut<0?reference:reference.substring(0,cut);}
    /** An attention item, with the case's main ID (for example the loan number) when the case has one; null otherwise. */
    public record AttentionResponse(String derivedDeliveryId,String packageId,String primaryIdentifierLabel,String primaryIdentifierValue,String sourceDocumentId,int exportCycle,String destinationLabel,String markerType,String markerValue,List<Integer> pageNumbers,String status,int attemptCount,String failureCategory,boolean retryable,String destinationReference,String deliveredAt){
        static AttentionResponse of(DerivedDeliveryAttentionQuery.AttentionItem item){
            Response r=Response.of(item.delivery());var id=item.caseIdentifier();
            return new AttentionResponse(r.derivedDeliveryId(),r.packageId(),id==null?null:id.label(),id==null?null:id.value(),r.sourceDocumentId(),r.exportCycle(),r.destinationLabel(),r.markerType(),r.markerValue(),r.pageNumbers(),r.status(),r.attemptCount(),r.failureCategory(),r.retryable(),r.destinationReference(),r.deliveredAt());
        }
    }
}
