package com.securedoc.platform.application.routing.service;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.IntegrationConnectionRepository;
import com.securedoc.platform.application.port.out.RoutingRuleRepository;
import com.securedoc.platform.application.routing.model.RoutingRuleInput;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.integration.IntegrationDirection;
import com.securedoc.platform.domain.integration.IntegrationPurpose;
import com.securedoc.platform.domain.routing.RoutingRule;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Business-owned routing map management. Technical destination details remain IT-owned Integration Connections. */
public final class RoutingRuleService {
    private final RoutingRuleRepository rules;
    private final IntegrationConnectionRepository connections;
    private final AuditService audit;
    public RoutingRuleService(RoutingRuleRepository rules, IntegrationConnectionRepository connections, AuditService audit) {
        this.rules=Objects.requireNonNull(rules); this.connections=Objects.requireNonNull(connections); this.audit=Objects.requireNonNull(audit);
    }
    public List<RoutingRule> list(TenantId tenantId){ return rules.findByTenantId(tenantId); }
    public RoutingRule get(TenantId tenantId, UUID id){ return rules.findByTenantIdAndId(tenantId,id).orElseThrow(()->new NotFoundException("RoutingRule",id.toString())); }
    public RoutingRule create(TenantId tenantId, ActorId actorId, CorrelationId correlationId, RoutingRuleInput input){
        validate(tenantId,input,null); RoutingRule rule;
        try { rule=RoutingRule.create(tenantId,input.destinationLabel(),input.markerType(),input.markerValue(),input.scanRegion(),input.destinationConnectionId(),input.enabled(),Instant.now()); }
        catch(IllegalArgumentException e){throw new ValidationException(e.getMessage());}
        rules.save(rule); audit.recordSuccess(tenantId,actorId, AuditAction.ROUTING_RULE_CREATED,"RoutingRule",rule.id().toString(),correlationId,safe(rule)); return rule;
    }
    public RoutingRule update(TenantId tenantId, UUID id, ActorId actorId, CorrelationId correlationId, RoutingRuleInput input){
        RoutingRule rule=get(tenantId,id); validate(tenantId,input,id);
        try { rule.update(input.destinationLabel(),input.markerType(),input.markerValue(),input.scanRegion(),input.destinationConnectionId(),input.enabled(),Instant.now()); }
        catch(IllegalArgumentException e){throw new ValidationException(e.getMessage());}
        rules.save(rule); audit.recordSuccess(tenantId,actorId,AuditAction.ROUTING_RULE_UPDATED,"RoutingRule",id.toString(),correlationId,safe(rule)); return rule;
    }
    public void delete(TenantId tenantId, UUID id, ActorId actorId, CorrelationId correlationId){ get(tenantId,id); rules.delete(tenantId,id); audit.recordSuccess(tenantId,actorId,AuditAction.ROUTING_RULE_DELETED,"RoutingRule",id.toString(),correlationId,"Routing rule removed"); }
    private void validate(TenantId tenantId, RoutingRuleInput input, UUID exclude){
        if(input==null) throw new ValidationException("Routing rule input is required");
        var c=connections.findByTenantIdAndId(tenantId,input.destinationConnectionId()).orElseThrow(()->new ValidationException("Derived delivery destination was not found for this tenant"));
        if(c.purpose()!= IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY || c.direction()!= IntegrationDirection.OUTBOUND) throw new ValidationException("Routing rule destination must be an outbound derived-document delivery connection");
        if(!c.enabled()) throw new ValidationException("Routing rule destination must be enabled before it can be mapped");
        if(rules.existsDuplicate(tenantId,input.markerType(),input.markerValue(),input.scanRegion(),exclude)) throw new ValidationException("A routing rule already exists for this marker and scan region");
    }
    private String safe(RoutingRule r){ return "markerType="+r.markerType()+",scanRegion="+r.scanRegion()+",destinationLabel="+r.destinationLabel()+",enabled="+r.enabled(); }
}
