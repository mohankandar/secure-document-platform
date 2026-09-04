package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.dto.IdentifierDefinitionResponse;
import com.securedoc.platform.application.business.service.BusinessIdentityService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/intake")
public class IntakeConfigurationController {
    private final BusinessIdentityService businessIdentityService;
    private final RequestContextResolver contextResolver;
    public IntakeConfigurationController(BusinessIdentityService businessIdentityService, RequestContextResolver contextResolver){
        this.businessIdentityService=businessIdentityService; this.contextResolver=contextResolver;
    }
    @GetMapping("/identifier-definitions")
    @RequireCapability(UserCapability.DOCUMENT_INTAKE)
    public ResponseEntity<List<IdentifierDefinitionResponse>> definitions(HttpServletRequest request){
        var principal=contextResolver.resolvePrincipal(request);
        var result=businessIdentityService.definitions(principal.tenantId()).stream()
                .map(d->new IdentifierDefinitionResponse(d.key(),d.label(),d.required(),d.primary(),d.searchable(),d.extractable(),d.displayable(),d.displayOrder()))
                .toList();
        return ResponseEntity.ok(result);
    }
}
