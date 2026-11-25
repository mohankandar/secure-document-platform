package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.dto.IdentifierDefinitionResponse;
import com.securedoc.platform.application.casefields.CaseFieldService;
import com.securedoc.platform.application.casefields.CaseFieldSettings;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/intake")
public class IntakeConfigurationController {
    private final CaseFieldService caseFields;
    private final CaseFieldSettings settings;
    private final RequestContextResolver contextResolver;
    public IntakeConfigurationController(CaseFieldService caseFields, CaseFieldSettings settings, RequestContextResolver contextResolver){
        this.caseFields=caseFields; this.settings=settings; this.contextResolver=contextResolver;
    }
    @GetMapping("/identifier-definitions")
    @RequireCapability(UserCapability.DOCUMENT_INTAKE)
    public ResponseEntity<List<IdentifierDefinitionResponse>> definitions(HttpServletRequest request){
        var principal=contextResolver.resolvePrincipal(request);
        var result=caseFields.definitions(principal.tenantId()).stream()
                .map(d->IdentifierDefinitionResponse.of(d, settings.extractable(principal.tenantId(), d)))
                .toList();
        return ResponseEntity.ok(result);
    }
}
