package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.application.intake.CaseInstructionsService;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.api.security.UserCapability;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Task 0065: a case's instructions for the AI, read and changed by a reviewer. Document text: never cached or logged. */
@RestController
@RequestMapping("/api/v1")
public class CaseInstructionsController {
    private final CaseInstructionsService instructions;
    private final TenantRedactionPolicyRepository policies;
    private final RequestContextResolver contextResolver;

    public CaseInstructionsController(CaseInstructionsService instructions, TenantRedactionPolicyRepository policies,
                                      RequestContextResolver contextResolver) {
        this.instructions = instructions;
        this.policies = policies;
        this.contextResolver = contextResolver;
    }

    /** @param enabled the entity's setting; while it is off no instructions are used */
    public record Instructions(boolean enabled, String text) {
        @Override
        public String toString() {
            return "Instructions[enabled=" + enabled + "]";
        }
    }

    public record Setting(boolean enabled, int maxCharacters) { }

    @GetMapping("/intake/case-instructions")
    @RequireCapability(UserCapability.DOCUMENT_INTAKE)
    public Setting setting(HttpServletRequest request) {
        return new Setting(enabled(request), CaseInstructionsService.MAX_CHARACTERS);
    }

    @GetMapping("/packages/{packageId}/instructions")
    @RequireCapability(UserCapability.MANUAL_REVIEW)
    public ResponseEntity<Instructions> read(@PathVariable UUID packageId, HttpServletRequest request) {
        boolean enabled = enabled(request);
        String text = enabled ? instructions.find(contextResolver.resolvePrincipal(request).tenantId(), PackageId.of(packageId))
                .map(CaseInstructionsService.Instructions::text).orElse("") : "";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Instructions(enabled, text));
    }

    @PutMapping("/packages/{packageId}/instructions")
    @RequireCapability(UserCapability.MANUAL_REVIEW)
    public Instructions change(@PathVariable UUID packageId, @RequestBody Instructions body, HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        instructions.change(principal.tenantId(), principal.actorId(), PackageId.of(packageId), body.text(),
                contextResolver.resolveCorrelationId(request));
        return new Instructions(enabled(request), body.text());
    }

    private boolean enabled(HttpServletRequest request) {
        return policies.findByTenantId(contextResolver.resolvePrincipal(request).tenantId())
                .map(TenantRedactionPolicy::documentContext).orElse(false);
    }
}
