package com.securedoc.platform.api.routing;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.routing.service.DerivedConnectionTestService;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/integrations/{connectionId}/derived")
public class DerivedIntegrationController {
    private final DerivedConnectionTestService service; private final RequestContextResolver context;
    public DerivedIntegrationController(DerivedConnectionTestService service,RequestContextResolver context){this.service=service;this.context=context;}
    @PostMapping("/test-connection") @RequireCapability(UserCapability.TENANT_INTEGRATION_MANAGE)
    public ResponseEntity<Map<String,Object>> test(@PathVariable String connectionId,HttpServletRequest request){var p=context.resolvePrincipal(request);var c=service.test(p.tenantId(),id(connectionId),p.actorId(),context.resolveCorrelationId(request));return ResponseEntity.ok(Map.of("connectionId",c.id().toString(),"healthStatus",c.healthStatus().name(),"configurationVersion",c.configurationVersion()));}
    private IntegrationConnectionId id(String v){try{return IntegrationConnectionId.fromString(v);}catch(Exception e){throw new ValidationException("Invalid integration connection id");}}
}
