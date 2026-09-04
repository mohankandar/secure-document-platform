package com.securedoc.platform.api.routing;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.exception.ValidationException;
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
    private final DerivedDeliveryService service; private final RequestContextResolver context;
    public DerivedDeliveryController(DerivedDeliveryService service,RequestContextResolver context){this.service=service;this.context=context;}
    @GetMapping("/packages/{packageId}/derived-deliveries") @RequireCapability(UserCapability.PACKAGE_APPROVE_DELIVERY)
    public List<Response> list(@PathVariable String packageId,HttpServletRequest request){var p=context.resolvePrincipal(request);return service.list(p.tenantId(),packageId(packageId)).stream().map(Response::of).toList();}
    @PostMapping("/packages/{packageId}/derived-deliveries/{deliveryId}/send") @RequireCapability(UserCapability.PACKAGE_APPROVE_DELIVERY)
    public ResponseEntity<Response> send(@PathVariable String packageId,@PathVariable String deliveryId,HttpServletRequest request){var p=context.resolvePrincipal(request);DerivedDelivery d=service.get(p.tenantId(),uuid(deliveryId));if(!d.packageId().equals(packageId(packageId)))throw new ValidationException("Derived delivery does not belong to this package");return ResponseEntity.ok(Response.of(service.send(p.tenantId(),d.id(),p.actorId(),context.resolveCorrelationId(request))));}
    @GetMapping("/derived-deliveries/attention") @RequireCapability(UserCapability.TECHNICAL_EXCEPTION_VIEW)
    public List<Response> attention(HttpServletRequest request){var p=context.resolvePrincipal(request);return service.listAttention(p.tenantId()).stream().map(Response::of).toList();}
    @PostMapping("/derived-deliveries/{deliveryId}/technical-retry") @RequireCapability(UserCapability.TECHNICAL_RETRY)
    public ResponseEntity<Response> retry(@PathVariable String deliveryId,HttpServletRequest request){var p=context.resolvePrincipal(request);return ResponseEntity.ok(Response.of(service.technicalRetry(p.tenantId(),uuid(deliveryId),p.actorId(),context.resolveCorrelationId(request))));}
    private UUID uuid(String v){try{return UUID.fromString(v);}catch(Exception e){throw new ValidationException("Invalid derived delivery id");}}private PackageId packageId(String v){try{return PackageId.of(UUID.fromString(v));}catch(Exception e){throw new ValidationException("Invalid package id");}}
    public record Response(String derivedDeliveryId,String packageId,String sourceDocumentId,int exportCycle,String destinationLabel,String markerType,String markerValue,List<Integer> pageNumbers,String fileName,String status,int attemptCount,String failureCategory,boolean retryable,String destinationReference,String deliveredAt){static Response of(DerivedDelivery d){return new Response(d.id().toString(),d.packageId().toString(),d.sourceDocumentId().toString(),d.exportCycle(),d.destinationLabel(),d.markerType()==null?null:d.markerType().name(),d.markerValue(),d.pageNumbers(),d.fileName(),d.status().name(),d.attemptCount(),d.lastFailureCategory(),d.retryable(),d.destinationReference(),d.deliveredAt()==null?null:d.deliveredAt().toString());}}
}
