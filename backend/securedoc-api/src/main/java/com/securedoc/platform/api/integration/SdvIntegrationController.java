package com.securedoc.platform.api.integration;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.integration.SdvConnectionTestService;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@ConditionalOnProperty(name = "securedoc.delivery.type", havingValue = "sdv")
@RequestMapping("/api/v1/integrations/{connectionId}/sdv")
public class SdvIntegrationController {
    private final SdvConnectionTestService service;
    private final RequestContextResolver contextResolver;

    public SdvIntegrationController(SdvConnectionTestService service, RequestContextResolver contextResolver) {
        this.service = service;
        this.contextResolver = contextResolver;
    }

    @PostMapping("/test-connection")
    @RequireCapability(UserCapability.TENANT_INTEGRATION_MANAGE)
    public ResponseEntity<Map<String,Object>> testConnection(@PathVariable String connectionId,
                                                              HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        var connection = service.test(principal.tenantId(), parseId(connectionId), principal.actorId(),
                contextResolver.resolveCorrelationId(request));
        return ResponseEntity.ok(Map.of(
                "connectionId", connection.id().value(),
                "healthStatus", connection.healthStatus().name(),
                "secretConfigured", connection.secretConfigured(),
                "configurationVersion", connection.configurationVersion()));
    }

    private IntegrationConnectionId parseId(String raw) {
        try { return IntegrationConnectionId.fromString(raw); }
        catch (RuntimeException e) { throw new ValidationException("Invalid integration connection id"); }
    }
}
