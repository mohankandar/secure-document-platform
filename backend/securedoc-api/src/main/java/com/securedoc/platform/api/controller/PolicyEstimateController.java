package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.business.admin.PolicyEstimateService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code GET /api/v1/business-admin/sensitive-data-policy/estimate?reviewMode=&threshold=} (task 0019 B2): how many
 * cases and findings a person would check in the last 30 days under the saved policy and under the proposed one,
 * for the entity the person is signed in to. Counts only; reading it is not audited.
 */
@RestController
@RequestMapping("/api/v1/business-admin")
public class PolicyEstimateController {

    private final PolicyEstimateService service;
    private final RequestContextResolver contextResolver;

    public PolicyEstimateController(PolicyEstimateService service, RequestContextResolver contextResolver) {
        this.service = service;
        this.contextResolver = contextResolver;
    }

    @GetMapping("/sensitive-data-policy/estimate")
    @RequireCapability(UserCapability.TENANT_POLICY_MANAGE)
    public ResponseEntity<Map<String, Object>> estimate(@RequestParam(required = false) String reviewMode,
                                                        @RequestParam(required = false) String threshold,
                                                        HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        PolicyEstimateService.Estimate e = service.estimate(principal.tenantId(), reviewMode, threshold);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("windowDays", e.windowDays());
        body.put("cases", e.cases());
        body.put("enoughData", e.enoughData());
        body.put("current", side(e.current()));
        body.put("proposed", side(e.proposed()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /** Under 20 cases the two counts are left out (no key), so nothing can be read as zero. */
    private static Map<String, Object> side(PolicyEstimateService.Side s) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("reviewMode", s.reviewMode().name());
        map.put("threshold", s.threshold());
        map.put("preset", s.preset().name());
        if (s.casesToPerson() != null) map.put("casesToPerson", s.casesToPerson());
        if (s.findingsToPerson() != null) map.put("findingsToPerson", s.findingsToPerson());
        return map;
    }
}
