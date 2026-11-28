package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.caseview.CaseViewService;
import com.securedoc.platform.application.caseview.CaseViewSettings;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Task 0108: the signed-in person's Cases-page view (View menu: sort, order, columns, density), always for the entity
 * the person is in. No call takes a person or an entity.
 */
@RestController
@RequestMapping("/api/v1/me/case-view")
public class CaseViewController {
    private final RequestContextResolver context;
    private final CaseViewService service;

    public CaseViewController(RequestContextResolver context, CaseViewService service) {
        this.context = context;
        this.service = service;
    }

    @GetMapping
    @RequireCapability(UserCapability.DASHBOARD_ACCESS)
    public ResponseEntity<CaseViewService.View> read(HttpServletRequest request) {
        AuthenticatedPrincipal principal = context.resolvePrincipal(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.read(principal.tenantId(), principal.actorId()));
    }

    @PutMapping
    @RequireCapability(UserCapability.DASHBOARD_ACCESS)
    public ResponseEntity<CaseViewService.View> save(@RequestBody CaseViewSettings body, HttpServletRequest request) {
        AuthenticatedPrincipal principal = context.resolvePrincipal(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.save(principal.tenantId(), principal.actorId(), body));
    }
}
