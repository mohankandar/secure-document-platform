package com.securedoc.platform.api.integration;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.intake.polling.*;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/integrations/{connectionId}/polling")
public class PollingIntegrationController {
    private final PollingIntakeService service;
    private final RequestContextResolver contextResolver;
    public PollingIntegrationController(PollingIntakeService service, RequestContextResolver contextResolver){this.service=service;this.contextResolver=contextResolver;}

    @GetMapping
    @RequireCapability(UserCapability.TENANT_INTEGRATION_MANAGE)
    public ResponseEntity<PollingConnectionStatusResponse> status(@PathVariable String connectionId, HttpServletRequest request){
        var principal=contextResolver.resolvePrincipal(request);
        return ResponseEntity.ok(toResponse(service.status(principal.tenantId(), parseId(connectionId))));
    }

    @PostMapping("/test-connection")
    @RequireCapability(UserCapability.TENANT_INTEGRATION_MANAGE)
    public ResponseEntity<PollingConnectionStatusResponse> testConnection(@PathVariable String connectionId, HttpServletRequest request){
        var principal=contextResolver.resolvePrincipal(request);
        return ResponseEntity.ok(toResponse(service.testConnection(principal.tenantId(), parseId(connectionId), principal.actorId())));
    }

    @PostMapping("/run-now")
    @RequireCapability(UserCapability.TENANT_INTEGRATION_MANAGE)
    public ResponseEntity<?> runNow(@PathVariable String connectionId, HttpServletRequest request){
        var principal=contextResolver.resolvePrincipal(request);
        var result=service.runNow(principal.tenantId(), parseId(connectionId), principal.actorId());
        return result.<ResponseEntity<?>>map(v -> ResponseEntity.ok(toResponse(v)))
                .orElseGet(() -> ResponseEntity.status(409).body(java.util.Map.of("code","POLL_ALREADY_RUNNING","message","A polling run is already active for this source")));
    }

    private PollingConnectionStatusResponse toResponse(PollingConnectionStatus s){return new PollingConnectionStatusResponse(s.connectionId(),s.name(),s.enabled(),s.healthStatus(),s.nextScheduledAt(),s.recentRuns().stream().map(this::toResponse).toList());}
    private PollingRunResponse toResponse(PollingRunSnapshot r){return new PollingRunResponse(r.runId(),r.connectionId(),r.trigger().name(),r.status().name(),r.startedAt(),r.completedAt(),r.packagesDiscovered(),r.documentsExpected(),r.documentsRetrieved(),r.packagesAcknowledged(),r.failureCategory(),r.failureDetail());}
    private IntegrationConnectionId parseId(String raw){try{return IntegrationConnectionId.fromString(raw);}catch(RuntimeException e){throw new ValidationException("Invalid integration connection id");}}
}
