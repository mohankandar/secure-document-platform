package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.casepage.CasePageSettings;
import com.securedoc.platform.application.casepage.CasePageSettingsService;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.domain.error.LvErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Task 0108 (addendum 2) and 0111 question 1: the entity's Cases-page settings in Admin (Business Admin): when waiting
 * time turns amber and red, and after how many days a case without documents expires. Always the signed-in person's
 * entity; no call takes an entity.
 */
@RestController
@RequestMapping("/api/v1/business-admin/case-page-settings")
public class CasePageSettingsController {
    private final RequestContextResolver context;
    private final CasePageSettingsService service;

    public CasePageSettingsController(RequestContextResolver context, CasePageSettingsService service) {
        this.context = context;
        this.service = service;
    }

    public record Body(Integer waitAmberMinutes, Integer waitRedMinutes, Integer emptyCaseExpiryDays) { }

    public record Response(int waitAmberMinutes, int waitRedMinutes, int emptyCaseExpiryDays,
                           int defaultEmptyCaseExpiryDays) {
        static Response of(CasePageSettings s, int defaultDays) {
            return new Response(s.waitAmberMinutes(), s.waitRedMinutes(), s.emptyCaseExpiryDays(), defaultDays);
        }
    }

    @GetMapping
    @RequireCapability(UserCapability.TENANT_POLICY_MANAGE)
    public ResponseEntity<Response> read(HttpServletRequest request) {
        AuthenticatedPrincipal principal = context.resolvePrincipal(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Response.of(service.read(principal.tenantId()), service.defaultExpiryDays()));
    }

    @PutMapping
    @RequireCapability(UserCapability.TENANT_POLICY_MANAGE)
    public ResponseEntity<Response> save(@RequestBody Body body, HttpServletRequest request) {
        if (body == null) throw new ValidationException(LvErrorCode.CFG_001, "The Cases page settings are missing");
        AuthenticatedPrincipal principal = context.resolvePrincipal(request);
        CasePageSettings saved = service.save(principal.tenantId(), principal.actorId(),
                context.resolveCorrelationId(request), body.waitAmberMinutes(), body.waitRedMinutes(),
                body.emptyCaseExpiryDays());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Response.of(saved, service.defaultExpiryDays()));
    }
}
