package com.securedoc.platform.api.routing;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.IntegrationConnectionRepository;
import com.securedoc.platform.application.routing.model.RoutingRuleInput;
import com.securedoc.platform.application.routing.service.RoutingRuleService;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.integration.IntegrationPurpose;
import com.securedoc.platform.domain.routing.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/business-admin/routing-rules")
public class RoutingRuleController {
    private final RoutingRuleService service; private final IntegrationConnectionRepository connections; private final RequestContextResolver context;
    public RoutingRuleController(RoutingRuleService service,IntegrationConnectionRepository connections,RequestContextResolver context){this.service=service;this.connections=connections;this.context=context;}
    @GetMapping @RequireCapability(UserCapability.TENANT_POLICY_MANAGE)
    public List<RuleResponse> list(HttpServletRequest request){var p=context.resolvePrincipal(request);return service.list(p.tenantId()).stream().map(RuleResponse::of).toList();}
    @GetMapping("/destinations") @RequireCapability(UserCapability.TENANT_POLICY_MANAGE)
    public List<DestinationResponse> destinations(HttpServletRequest request){var p=context.resolvePrincipal(request);return connections.findByTenantId(p.tenantId()).stream().filter(c->c.purpose()== IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY).map(c->new DestinationResponse(c.id().toString(),c.name(),c.providerType().name(),c.enabled(),c.healthStatus().name())).toList();}
    @PostMapping @RequireCapability(UserCapability.TENANT_POLICY_MANAGE)
    public ResponseEntity<RuleResponse> create(@RequestBody RuleRequest body,HttpServletRequest request){var p=context.resolvePrincipal(request);return ResponseEntity.ok(RuleResponse.of(service.create(p.tenantId(),p.actorId(),context.resolveCorrelationId(request),input(body))));}
    @PutMapping("/{ruleId}") @RequireCapability(UserCapability.TENANT_POLICY_MANAGE)
    public ResponseEntity<RuleResponse> update(@PathVariable String ruleId,@RequestBody RuleRequest body,HttpServletRequest request){var p=context.resolvePrincipal(request);return ResponseEntity.ok(RuleResponse.of(service.update(p.tenantId(),uuid(ruleId),p.actorId(),context.resolveCorrelationId(request),input(body))));}
    @DeleteMapping("/{ruleId}") @RequireCapability(UserCapability.TENANT_POLICY_MANAGE)
    public ResponseEntity<Void> delete(@PathVariable String ruleId,HttpServletRequest request){var p=context.resolvePrincipal(request);service.delete(p.tenantId(),uuid(ruleId),p.actorId(),context.resolveCorrelationId(request));return ResponseEntity.noContent().build();}
    private RoutingRuleInput input(RuleRequest b){if(b==null)throw new ValidationException("Routing rule request is required");try{return new RoutingRuleInput(b.destinationLabel(),RoutingMarkerType.valueOf(req(b.markerType(),"marker type")),b.markerValue(),RoutingScanRegion.valueOf(req(b.scanRegion(),"scan region")),IntegrationConnectionId.fromString(req(b.destinationConnectionId(),"destination connection")),Boolean.TRUE.equals(b.enabled()));}catch(IllegalArgumentException e){throw new ValidationException("Invalid routing rule configuration");}}
    private String req(String v,String label){if(v==null||v.isBlank())throw new ValidationException("Routing "+label+" is required");return v.trim().toUpperCase(Locale.ROOT);}private UUID uuid(String v){try{return UUID.fromString(v);}catch(Exception e){throw new ValidationException("Invalid routing rule id");}}
    public record RuleRequest(String destinationLabel,String markerType,String markerValue,String scanRegion,String destinationConnectionId,Boolean enabled){}
    public record RuleResponse(String ruleId,String destinationLabel,String markerType,String markerValue,String scanRegion,String destinationConnectionId,boolean enabled){static RuleResponse of(RoutingRule r){return new RuleResponse(r.id().toString(),r.destinationLabel(),r.markerType().name(),r.markerValue(),r.scanRegion().name(),r.destinationConnectionId().toString(),r.enabled());}}
    public record DestinationResponse(String connectionId,String name,String providerType,boolean enabled,String healthStatus){}
}
